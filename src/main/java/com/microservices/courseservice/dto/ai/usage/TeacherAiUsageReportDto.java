package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TeacherAiUsageReportDto {
    private AiUsagePeriodDto period;
    private AiUsageSummaryDto summary;
    private List<AiUsageByModelDto> byModel;
    private List<AiUsageTimeSeriesPointDto> timeSeries;
    private List<AiUsageRecentRunDto> recentRuns;
    private List<AiQuotaUtilizationDto> quotaUtilization;
}
