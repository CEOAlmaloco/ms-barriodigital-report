package cl.duoc.barriodigital.report.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OracleWalletConfigurationTest {

    private static final String TNS_ADMIN = "oracle.net.tns_admin";

    @TempDir
    Path temp;

    @AfterEach
    void clearProperty() {
        System.clearProperty(TNS_ADMIN);
    }

    private void process(String walletDir) {
        var processor = new OracleWalletConfiguration.OracleWalletTnsAdminPostProcessor();
        var environment = new MockEnvironment();
        if (walletDir != null) {
            environment.setProperty("ORACLE_WALLET_DIR", walletDir);
        }
        processor.setEnvironment(environment);
        processor.postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    @Test
    void sinVariableNoTocaLaPropiedad() {
        process(null);
        process("   ");
        assertThat(System.getProperty(TNS_ADMIN)).isNull();
    }

    @Test
    void carpetaInexistenteImpideArrancar() {
        assertThatThrownBy(() -> process(temp.resolve("no-existe").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORACLE_WALLET_DIR no existe");
        assertThat(System.getProperty(TNS_ADMIN)).isNull();
    }

    @Test
    void sinTnsnamesImpideArrancar() {
        assertThatThrownBy(() -> process(temp.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("falta tnsnames.ora");
        assertThat(System.getProperty(TNS_ADMIN)).isNull();
    }

    @Test
    void walletValidoFijaTnsAdmin() throws Exception {
        Files.writeString(temp.resolve("tnsnames.ora"), "barriodig_low = (DESCRIPTION=)");
        process(" " + temp + " ");
        assertThat(System.getProperty(TNS_ADMIN)).isEqualTo(temp.toAbsolutePath().toString());
    }
}
