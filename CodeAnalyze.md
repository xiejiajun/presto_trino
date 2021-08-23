## Trino源码分析
### Trino数据源计算下推流程分析
- MySQL聚合下推为例：LogicalPlanner#plan -> IterativeOptimizer#optimize -> IterativeOptimizer#exploreGroup -> IterativeOptimizer#exploreNode -> IterativeOptimizer#transform 
  -> PushAggregationIntoTableScan#apply：这里是实现了Rule公共接口的RBO优化器 -> PushAggregationIntoTableScan#pushAggregationIntoTableScan -> MetadataManager#applyAggregation 
  -> DefaultJdbcMetadata.applyAggregation -> MySqlClient.implementAggregation -> AggregateFunctionRewriter#rewrite
  -> io.trino.plugin.mysql.ImplementAvgBigint#rewrite
