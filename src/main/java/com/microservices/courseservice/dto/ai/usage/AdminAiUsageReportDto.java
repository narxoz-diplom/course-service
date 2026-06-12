package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminAiUsageReportDto {
    private AiUsagePeriodDto period;
    private AiUsageSummaryDto summary;
    private List<AiUsageByModelDto> byModel;
    private List<AiUsageByProviderDto> byProvider;
    private List<AiUsageTopUserDto> topUsers;
    private List<AiUsageTimeSeriesPointDto> timeSeries;
    private List<AiQuotaUtilizationDto> quotaUtilization;
}
