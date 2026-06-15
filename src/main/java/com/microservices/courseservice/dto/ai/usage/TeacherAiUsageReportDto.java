package com.microservices.courseservice.dto.ai.usage;

import com.microservices.courseservice.dto.ai.TeacherAiLimitStatusDto;
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
    /** Per-teacher account quota (default limits + optional admin override). */
    private TeacherAiLimitStatusDto userLimit;
}
