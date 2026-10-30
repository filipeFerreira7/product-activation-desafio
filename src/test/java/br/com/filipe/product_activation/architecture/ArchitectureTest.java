package br.com.filipe.product_activation.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final String BASE = "br.com.filipe.product_activation";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(BASE);

    @Test
    void controllersShouldNotDependOnRepositoryOrIntegration() {
        noClasses()
                .that().resideInAPackage(BASE + ".controller..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".repository..", BASE + ".integration..")
                .because("o controller orquestra via service, nunca via camada de persistencia/integracao")
                .check(classes);
    }

    @Test
    void servicesShouldNotDependOnControllers() {
        noClasses()
                .that().resideInAPackage(BASE + ".service..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".controller..")
                .because("dependencia deve apontar para dentro (service nao conhece a web)")
                .check(classes);
    }

    @Test
    void modelShouldNotDependOnAnyLayerAboveIt() {
        noClasses()
                .that().resideInAPackage(BASE + ".model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".controller..", BASE + ".service..", BASE + ".repository..",
                        BASE + ".integration..", BASE + ".dto..", BASE + ".config..")
                .because("o dominio e o nucleo estavel da aplicacao")
                .check(classes);
    }

    @Test
    void repositoriesShouldBeInterfaces() {
        classes()
                .that().resideInAPackage(BASE + ".repository..")
                .should().beInterfaces()
                .because("abstracao de acesso a dados permite troca de implementacao")
                .check(classes);
    }

    @Test
    void dtosShouldNotAccessRepositoryOrIntegration() {
        noClasses()
                .that().resideInAPackage(BASE + ".dto..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".repository..", BASE + ".integration..")
                .because("DTOs sao apenas contratos de entrada/saida")
                .check(classes);
    }
}
