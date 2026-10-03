package com.zeus.framework.soap;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.cxf.Bus;
import org.apache.cxf.bus.extension.ExtensionManagerBus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link ZeusSoapAutoConfiguration}'ın yetenek opt-in'ine uyduğunun kanıtı (fix round 2, kusur 1).
 *
 * <p>KUSUR NEYDİ: bu autoconfig yalnız {@code @ConditionalOnClass(Bus.class)} ile koşulluydu.
 * {@code zeus.soap.enabled=false} yazan bir uygulamada — ki bu cümleyi framework'ün KENDİ hata
 * mesajı öneriyor ({@code ZeusCapabilityVerifier}: "ya da bilinçli olarak kapalı tutmak için:
 * zeus.soap.enabled=false") — CXF'in {@code CxfAutoConfiguration}'ı filtre tarafından veto
 * edilir, dolayısıyla {@code Bus} bean'i HİÇ kurulmaz; ama bu sınıf yine de yüklenip
 * {@code zeusSoapEndpointRegistrar(Bus, ...)} bean'ini kaydettiği için açılış
 * {@code UnsatisfiedDependencyException → NoSuchBeanDefinitionException: ... 'org.apache.cxf.Bus'}
 * ile DÜŞÜYORDU.
 *
 * <p>Aşağıdaki koşular tam o üretim koşulunu taklit eder: {@code Bus} bean'i context'te YOKTUR
 * (çünkü gerçek koşuda CXF'in autoconfig'i veto edilmiştir).
 */
class ZeusSoapAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ZeusSoapAutoConfiguration.class));

    @Test
    void bilincliFalseAcilisiCokertmez() {
        runner.withPropertyValues("zeus.soap.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ZeusSoapAutoConfiguration.class)
                        .doesNotHaveBean(ZeusSoapEndpointRegistrar.class));
    }

    @Test
    void propertyHicYazilmamissaDaAcilisCokmez() {
        // Yetenekler OPT-IN'dir: bildirimi olmayan uygulama da Bus'sız ayakta kalmalı.
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(ZeusSoapEndpointRegistrar.class));
    }

    @Test
    void trueYazilincaRegistrarKurulur() {
        // Gerçek koşuda Bus'ı CXF'in kendi autoconfig'i kurar (yetenek açıkken veto edilmez);
        // burada onun yerine gerçek bir Bus bean'i verilir.
        runner.withPropertyValues("zeus.soap.enabled=true")
                .withBean(Bus.class, ExtensionManagerBus::new)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(ZeusSoapAutoConfiguration.class)
                        .hasSingleBean(ZeusSoapEndpointRegistrar.class));
    }
}
