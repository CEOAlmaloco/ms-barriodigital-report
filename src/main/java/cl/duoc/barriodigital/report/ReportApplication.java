package cl.duoc.barriodigital.report;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Arranca el servicio de reportería (KPIs y plazos).
 */
@SpringBootApplication
public class ReportApplication {

    /** Inicia Spring Boot con esta clase como configuración. */
    public static void main(String[] args) {
        SpringApplication.run(ReportApplication.class, args);
    }
}
