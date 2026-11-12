package cl.duoc.barriodigital.report.config;

import cl.duoc.barriodigital.report.domain.BusinessCalendar;
import cl.duoc.barriodigital.report.domain.SlaCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HolidaysProperties.class)
public class SlaConfig {

    @Bean
    public BusinessCalendar businessCalendar(HolidaysProperties holidays) {
        return new BusinessCalendar(holidays.dates(), holidays.years().keySet());
    }

    @Bean
    public SlaCalculator slaCalculator(BusinessCalendar calendar,
                                       @Value("${barriodigital.sla.due-soon-business-days}") int dueSoonBusinessDays) {
        return new SlaCalculator(calendar, dueSoonBusinessDays);
    }
}
