-- This file is automatically generated by LogicalPlanToSQLSuite.
SELECT exp.id, parquet_t3.id
FROM parquet_t3
LATERAL VIEW EXPLODE(arr) exp AS id
--------------------------------------------------------------------------------
SELECT `gen_attr_0` AS `id`, `gen_attr_1` AS `id` FROM (SELECT `gen_attr_0`, `gen_attr_1` FROM (SELECT `arr` AS `gen_attr_2`, `arr2` AS `gen_attr_3`, `json` AS `gen_attr_4`, `id` AS `gen_attr_1` FROM `default`.`parquet_t3`) AS gen_subquery_0 LATERAL VIEW explode(`gen_attr_2`) gen_subquery_2 AS `gen_attr_0`) AS gen_subquery_1
