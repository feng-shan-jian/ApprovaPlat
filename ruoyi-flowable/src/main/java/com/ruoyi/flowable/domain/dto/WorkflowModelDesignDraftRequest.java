package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WorkflowModelDesignDraftRequest(
        @NotNull @Min(1) Integer baseModelRevision,
        @NotNull @Min(0) Integer expectedRevision,
        @NotBlank String bpmnXml)
{
}
