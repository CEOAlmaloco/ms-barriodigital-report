package cl.duoc.barriodigital.report.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fija la carpeta del wallet como TNS_ADMIN antes de crear el DataSource.
 * Si la carpeta no existe o le falta tnsnames.ora, el servicio no arranca.
 */
@Configuration
public class OracleWalletConfiguration {

    /** Registra el paso que corre antes de abrir Oracle. */
    @Bean
    static BeanFactoryPostProcessor oracleWalletTnsAdmin() {
        return new OracleWalletTnsAdminPostProcessor();
    }

    /**
     * Lee ORACLE_WALLET_DIR y la deja en oracle.net.tns_admin.
     * El driver necesita esa carpeta para encontrar tnsnames.ora antes de conectar.
     */
    static final class OracleWalletTnsAdminPostProcessor
            implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        /** Guarda el entorno para leer la variable en el postproceso. */
        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        /**
         * Si hay wallet, comprueba la carpeta y tnsnames.ora, y fija la propiedad del driver.
         * Sin la variable no hace nada y deja la conexión como esté configurada.
         */
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            String walletDir = environment.getProperty("ORACLE_WALLET_DIR");
            if (!StringUtils.hasText(walletDir)) {
                return;
            }
            Path path = Path.of(walletDir.trim());
            if (!Files.isDirectory(path)) {
                throw new IllegalStateException(
                        "ORACLE_WALLET_DIR no existe o no es una carpeta: " + path
                                + ". Descomprimí el wallet de Autonomous ahí (tiene que haber tnsnames.ora)."
                );
            }
            if (!Files.isRegularFile(path.resolve("tnsnames.ora"))) {
                throw new IllegalStateException(
                        "En ORACLE_WALLET_DIR falta tnsnames.ora: " + path
                                + ". Revisá que hayas descomprimido el ZIP completo del wallet."
                );
            }
            System.setProperty("oracle.net.tns_admin", path.toAbsolutePath().toString());
        }
    }
}
