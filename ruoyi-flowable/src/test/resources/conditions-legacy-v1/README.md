# C10 前部署资源兼容 fixture

这些字节于 2026-09-17 22:13:54 +08:00 在干净基线
`0fc302303e524bf658e0272bd66459ad9a8a25f3` 的生产代码上生成，在修改生产类之前冻结。
临时 `ConditionLegacyFixtureGeneratorTest` 使用作者 `approva.conditionRule.config`、正式 NUMBER 字段契约，调用
`WorkflowConditionDeploymentService.prepare`，将编译 BPMN 交给真实 Flowable/H2 部署，再调用
`WorkflowDeploymentArtifactRepository.persist`，最后用 RepositoryService 逐个读取原始资源字节。
生成命令：`mvn -pl ruoyi-flowable -am -Dtest=ConditionLegacyFixtureGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test -B -ntp`。
结果 1 test / 0 failures / 0 errors，24.703 秒；生成器已按临时文件约定删除。

每个目录保存真实父部署 BPMN 和全部九个子部署 JSON 资源。金额边界为整数
9007199254740993：分支 a 使用 GTE，b 使用 GT，另有默认流；分别覆盖排他歧义拒绝和包容多命中。
createTime、deployId、原始 CEL、原始 checksum 均未经重写。兼容测试按原始字节安装资源；
安装时只建立新的 Flowable 父子部署关系（部署主键不属于条件摘要），不调用当前 checksum 或编译器生成旧数据。

关键原始 SHA-256：

| 路径 | SHA-256 |
| --- | --- |
| exclusive/process.bpmn20.xml | c9b4878a75815ceef5d3a3c657dc876a41a3990e5fc8d7dbbd573a0cd9d04782 |
| exclusive/conditions-v1.json | 4e6e9a40d2f66a4d6db3723925449b25df22c631c65569436e1c810ec54388d8 |
| inclusive/process.bpmn20.xml | ff22b8ef12c5a401d7fee87ae0eb3877f57d53f746f3b34656d960f6c7b40242 |
| inclusive/conditions-v1.json | 7b38cbfc6c8d7393f111689e2fe5b40ba8e3d19e7ba8cdc62e855cc40db40b75 |
| 两份 manifest-v1.json | 0e9561cfb83d50990a103b3896fe249a11fe27fa28985448187f93ec12116d72 |
| 其余空列表资源（`[]`） | 4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945 |
