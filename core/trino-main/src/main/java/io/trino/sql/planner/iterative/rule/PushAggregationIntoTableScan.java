/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import io.trino.Session;
import io.trino.matching.Capture;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.metadata.BoundSignature;
import io.trino.metadata.Metadata;
import io.trino.metadata.TableHandle;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.AggregationApplicationResult;
import io.trino.spi.connector.Assignment;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.SortItem;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Variable;
import io.trino.spi.predicate.TupleDomain;
import io.trino.sql.planner.ConnectorExpressionTranslator;
import io.trino.sql.planner.LiteralEncoder;
import io.trino.sql.planner.OrderingScheme;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.plan.AggregationNode;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.sql.planner.plan.TableScanNode;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.SymbolReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.trino.SystemSessionProperties.isAllowPushdownIntoConnectors;
import static io.trino.matching.Capture.newCapture;
import static io.trino.sql.planner.iterative.rule.Rules.deriveTableStatisticsForPushdown;
import static io.trino.sql.planner.plan.Patterns.Aggregation.step;
import static io.trino.sql.planner.plan.Patterns.aggregation;
import static io.trino.sql.planner.plan.Patterns.source;
import static io.trino.sql.planner.plan.Patterns.tableScan;

public class PushAggregationIntoTableScan
        implements Rule<AggregationNode>
{
    private static final Capture<TableScanNode> TABLE_SCAN = newCapture();

    private static final Pattern<AggregationNode> PATTERN =
            aggregation()
                    .with(step().equalTo(AggregationNode.Step.SINGLE))
                    // skip arguments that are, for instance, lambda expressions
                    .matching(PushAggregationIntoTableScan::allArgumentsAreSimpleReferences)
                    .matching(node -> node.getGroupingSets().getGroupingSetCount() <= 1)
                    .matching(PushAggregationIntoTableScan::hasNoMasks)
                    .with(source().matching(tableScan().capturedAs(TABLE_SCAN)));

    private final Metadata metadata;

    public PushAggregationIntoTableScan(Metadata metadata)
    {
        this.metadata = metadata;
    }

    @Override
    public Pattern<AggregationNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public boolean isEnabled(Session session)
    {
        return isAllowPushdownIntoConnectors(session);
    }

    private static boolean allArgumentsAreSimpleReferences(AggregationNode node)
    {
        return node.getAggregations()
                .values().stream()
                .flatMap(aggregation -> aggregation.getArguments().stream())
                .allMatch(SymbolReference.class::isInstance);
    }

    private static boolean hasNoMasks(AggregationNode node)
    {
        return node.getAggregations()
                .values().stream()
                .allMatch(aggregation -> aggregation.getMask().isEmpty());
    }

    @Override
    public Result apply(AggregationNode node, Captures captures, Context context)
    {
        // TODO 聚合下推
        return pushAggregationIntoTableScan(metadata, context, node, captures.get(TABLE_SCAN), node.getAggregations(), node.getGroupingSets().getGroupingKeys())
                .map(Rule.Result::ofPlanNode)
                .orElseGet(Rule.Result::empty);
    }

    public static Optional<PlanNode> pushAggregationIntoTableScan(
            Metadata metadata,
            Context context,
            PlanNode aggregationNode,
            TableScanNode tableScan,
            Map<Symbol, AggregationNode.Aggregation> aggregations,
            List<Symbol> groupingKeys)
    {
        Map<String, ColumnHandle> assignments = tableScan.getAssignments()
                .entrySet().stream()
                .collect(toImmutableMap(entry -> entry.getKey().getName(), Entry::getValue));

        List<Entry<Symbol, AggregationNode.Aggregation>> aggregationsList = aggregations
                .entrySet().stream()
                .collect(toImmutableList());

        List<AggregateFunction> aggregateFunctions = aggregationsList.stream()
                .map(Entry::getValue)
                .map(aggregation -> toAggregateFunction(context, aggregation))
                .collect(toImmutableList());

        List<Symbol> aggregationOutputSymbols = aggregationsList.stream()
                .map(Entry::getKey)
                .collect(toImmutableList());

        List<ColumnHandle> groupByColumns = groupingKeys.stream()
                .map(groupByColumn -> assignments.get(groupByColumn.getName()))
                .collect(toImmutableList());

        // TODO 聚合下推(以MySQL聚合下推为例)：MetadataManager#applyAggregation -> DefaultJdbcMetadata.applyAggregation -> MySqlClient.implementAggregation -> AggregateFunctionRewriter#rewrite
        //  -> io.trino.plugin.mysql.ImplementAvgBigint#rewrite
        Optional<AggregationApplicationResult<TableHandle>> aggregationPushdownResult = metadata.applyAggregation(
                context.getSession(),
                tableScan.getTable(),
                aggregateFunctions,
                assignments,
                ImmutableList.of(groupByColumns));

        if (aggregationPushdownResult.isEmpty()) {
            return Optional.empty();
        }

        AggregationApplicationResult<TableHandle> result = aggregationPushdownResult.get();

        // The new scan outputs should be the symbols associated with grouping columns plus the symbols associated with aggregations.
        ImmutableList.Builder<Symbol> newScanOutputs = new ImmutableList.Builder<>();
        newScanOutputs.addAll(tableScan.getOutputSymbols());

        ImmutableBiMap.Builder<Symbol, ColumnHandle> newScanAssignments = new ImmutableBiMap.Builder<>();
        newScanAssignments.putAll(tableScan.getAssignments());

        Map<String, Symbol> variableMappings = new HashMap<>();

        for (Assignment assignment : result.getAssignments()) {
            Symbol symbol = context.getSymbolAllocator().newSymbol(assignment.getVariable(), assignment.getType());

            newScanOutputs.add(symbol);
            newScanAssignments.put(symbol, assignment.getColumn());
            variableMappings.put(assignment.getVariable(), symbol);
        }

        List<Expression> newProjections = result.getProjections().stream()
                .map(expression -> ConnectorExpressionTranslator.translate(expression, variableMappings, new LiteralEncoder(metadata)))
                .collect(toImmutableList());

        verify(aggregationOutputSymbols.size() == newProjections.size());

        Assignments.Builder assignmentBuilder = Assignments.builder();
        IntStream.range(0, aggregationOutputSymbols.size())
                .forEach(index -> assignmentBuilder.put(aggregationOutputSymbols.get(index), newProjections.get(index)));

        ImmutableBiMap<Symbol, ColumnHandle> scanAssignments = newScanAssignments.build();
        ImmutableBiMap<ColumnHandle, Symbol> columnHandleToSymbol = scanAssignments.inverse();
        // projections assignmentBuilder should have both agg and group by so we add all the group bys as symbol references
        groupingKeys
                .forEach(groupBySymbol -> {
                    // if the connector returned a new mapping from oldColumnHandle to newColumnHandle, groupBy needs to point to
                    // new columnHandle's symbol reference, otherwise it will continue pointing at oldColumnHandle.
                    ColumnHandle originalColumnHandle = assignments.get(groupBySymbol.getName());
                    ColumnHandle groupByColumnHandle = result.getGroupingColumnMapping().getOrDefault(originalColumnHandle, originalColumnHandle);
                    assignmentBuilder.put(groupBySymbol, columnHandleToSymbol.get(groupByColumnHandle).toSymbolReference());
                });

        return Optional.of(
                new ProjectNode(
                        context.getIdAllocator().getNextId(),
                        new TableScanNode(
                                context.getIdAllocator().getNextId(),
                                result.getHandle(),
                                newScanOutputs.build(),
                                scanAssignments,
                                TupleDomain.all(),
                                deriveTableStatisticsForPushdown(context.getStatsProvider(), context.getSession(), result.isPrecalculateStatistics(), aggregationNode),
                                tableScan.isUpdateTarget(),
                                // table scan partitioning might have changed with new table handle
                                Optional.empty()),
                        assignmentBuilder.build()));
    }

    private static AggregateFunction toAggregateFunction(Context context, AggregationNode.Aggregation aggregation)
    {
        BoundSignature signature = aggregation.getResolvedFunction().getSignature();

        ImmutableList.Builder<ConnectorExpression> arguments = new ImmutableList.Builder<>();
        for (int i = 0; i < aggregation.getArguments().size(); i++) {
            SymbolReference argument = (SymbolReference) aggregation.getArguments().get(i);
            arguments.add(new Variable(argument.getName(), signature.getArgumentTypes().get(i)));
        }

        Optional<OrderingScheme> orderingScheme = aggregation.getOrderingScheme();
        Optional<List<SortItem>> sortBy = orderingScheme.map(OrderingScheme::toSortItems);

        Optional<ConnectorExpression> filter = aggregation.getFilter()
                .map(symbol -> new Variable(symbol.getName(), context.getSymbolAllocator().getTypes().get(symbol)));

        return new AggregateFunction(
                signature.getName(),
                signature.getReturnType(),
                arguments.build(),
                sortBy.orElse(ImmutableList.of()),
                aggregation.isDistinct(),
                filter);
    }
}
