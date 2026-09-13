package com.ruoyi.flowable.domain;

/** 未提交上传的清理候选；认领次数同时防止过期上传重新写入。 */
public record WfAttachmentUpload(String storageKey, int claimCount)
{
}
