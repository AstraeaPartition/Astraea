-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT key, value, ROUND(AVG(key) OVER (), 2)
FROM parquet_t1 ORDER BY key nulls last
--------------------------------------------------------------------------------
SELECT `gen_attr_0` AS `key`, `gen_attr_1` AS `value`, `gen_attr_2` AS `round(avg(key) OVER (ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING), 2)` FROM (SELECT `gen_attr_0`, `gen_attr_1`, round(`gen_attr_3`, 2) AS `gen_attr_2` FROM (SELECT gen_subquery_1.`gen_attr_0`, gen_subquery_1.`gen_attr_1`, avg(`gen_attr_0`) OVER (ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS `gen_attr_3` FROM (SELECT `gen_attr_0`, `gen_attr_1` FROM (SELECT `key` AS `gen_attr_0`, `value` AS `gen_attr_1` FROM `default`.`parquet_t1`) AS gen_subquery_0) AS gen_subquery_1) AS gen_subquery_2 ORDER BY `gen_attr_0` ASC NULLS LAST) AS parquet_t1
