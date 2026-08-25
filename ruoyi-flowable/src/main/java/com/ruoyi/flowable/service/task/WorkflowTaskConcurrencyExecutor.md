# WorkflowTaskConcurrencyExecutor

## 作用

包裹单次任务并发敏感写入，把 Flowable 对象消失和乐观锁异常翻译为既有稳定 409。组件执行一次写尝试，并保持 Flowable revision 优先、业务 CAS 随后的锁顺序。

```java
concurrencyExecutor.execute(() -> taskService.complete(taskId, variables));
```
