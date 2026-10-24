package com.acme.trade.architecture;

import com.acme.trade.application.port.AuditRepository;
import com.acme.trade.domain.security.UserAccount;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Executable counterparts of concept-rules/AR-*. Rule IDs are intentional review anchors. */
class ArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.acme.trade");

    @Test
    void ar001_domain_is_pure_jdk() {
        classes().that().resideInAPackage("..domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..domain..")
                .because("AR-001: domain code must run without Spring, Redis, JDBC or Jackson")
                .check(PRODUCTION);
    }

    @Test
    void ar002_application_depends_only_on_domain_and_ports() {
        classes().that().resideInAPackage("..application..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..domain..", "..application..")
                .because("AR-002: frameworks enter only through application ports")
                .check(PRODUCTION);
    }

    @Test
    void ar003_web_cannot_bypass_application_services() {
        noClasses().that().resideInAPackage("..adapter.web..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..adapter.persistence..", "..adapter.redis..", "..adapter.transaction..")
                .because("AR-003: the HTTP layer cannot call infrastructure repositories directly")
                .check(PRODUCTION);
    }

    @Test
    void ar004_no_implicit_transaction_or_field_injection() {
        noMethods().should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .because("AR-004: transaction boundaries belong in TransactionRunner")
                .check(PRODUCTION);
        noClasses().should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional").check(PRODUCTION);
        noFields().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired").check(PRODUCTION);
        noMethods().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired").check(PRODUCTION);
    }

    @Test
    void ar005_adapters_are_explicitly_wired_not_scanned() {
        String[] stereotypes = {
                "org.springframework.stereotype.Component",
                "org.springframework.stereotype.Service",
                "org.springframework.stereotype.Repository",
                "org.springframework.stereotype.Controller",
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.context.annotation.Configuration"
        };
        for (String stereotype : stereotypes) {
            noClasses().that().resideInAPackage("..adapter..")
                    .should().beAnnotatedWith(stereotype)
                    .because("AR-005: adapters are constructed only by the composition roots")
                    .check(PRODUCTION);
        }
    }

    @Test
    void ar006_no_hidden_background_or_cache_semantics() {
        String[] hiddenEffects = {
                "org.springframework.scheduling.annotation.Async",
                "org.springframework.scheduling.annotation.Scheduled",
                "org.springframework.cache.annotation.Cacheable"
        };
        for (String annotation : hiddenEffects) {
            noMethods().should().beAnnotatedWith(annotation)
                    .because("AR-006: asynchronous, scheduled and cache effects must be explicit design decisions")
                    .check(PRODUCTION);
        }
    }

    @Test
    void ar007_top_level_packages_have_no_dependency_cycles() {
        slices().matching("com.acme.trade.(*)..").should().beFreeOfCycles()
                .because("AR-007: domain <- application <- adapters <- config must remain acyclic")
                .check(PRODUCTION);
    }

    @Test
    void ar008_core_has_no_hidden_threads_security_context_or_system_clock() {
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage("java.util.concurrent..")
                .because("AR-008: core use cases cannot hide executor or lock state")
                .check(PRODUCTION);
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().callMethod(System.class, "currentTimeMillis")
                .because("AR-008: time enters through Clock")
                .check(PRODUCTION);
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().callMethod(System.class, "nanoTime").check(PRODUCTION);
    }

    @Test
    void ar009_application_services_are_final_and_have_only_private_final_fields() {
        classes().that().resideInAPackage("..application.service..").should().haveModifier(JavaModifier.FINAL)
                .because("AR-009: services are closed composition units, not proxy subclasses")
                .check(PRODUCTION);
        fields().that().areDeclaredInClassesThat().resideInAPackage("..application.service..")
                .should().bePrivate().check(PRODUCTION);
        fields().that().areDeclaredInClassesThat().resideInAPackage("..application.service..")
                .should().beFinal().check(PRODUCTION);
    }

    @Test
    void ar010_infrastructure_adapters_do_not_call_each_other() {
        noClasses().that().resideInAPackage("..adapter.persistence..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter.redis..", "..adapter.web..", "..adapter.security..",
                        "..adapter.observability..", "..adapter.transaction..")
                .because("AR-010: persistence ports stay independent from other infrastructure concerns")
                .check(PRODUCTION);
        noClasses().that().resideInAPackage("..adapter.redis..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter.persistence..", "..adapter.web..", "..adapter.security..",
                        "..adapter.observability..", "..adapter.transaction..")
                .check(PRODUCTION);
    }

    @Test
    void ar011_security_is_stateless_and_has_no_threadlocal_or_cookie_identity() {
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName(ThreadLocal.class.getName())
                .because("AR-011: Actor and raw token travel explicitly, never through ThreadLocal")
                .check(PRODUCTION);
        noClasses().that().resideInAPackage("..adapter.security..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.security.core.context..")
                .check(PRODUCTION);
        // Optional Cookie delivery parses the Cookie header as text (CookieAuthSupport) and never
        // depends on jakarta.servlet.http.Cookie — blocks silent Servlet-session / cookie APIs.
        noClasses().that().resideInAPackage("..adapter.security..")
                .should().dependOnClassesThat().haveFullyQualifiedName(jakarta.servlet.http.Cookie.class.getName())
                .because("AR-011: no jakarta.servlet.http.Cookie; optional __Host- delivery is header-parsed, not Servlet Cookie identity")
                .check(PRODUCTION);
    }

    @Test
    void ar012_http_mappings_remain_explicit_router_functions() {
        String[] mappings = {
                "org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.PatchMapping"
        };
        for (String mapping : mappings) {
            noMethods().should().beAnnotatedWith(mapping)
                    .because("AR-012: no annotation-discovered duplicate routes")
                    .check(PRODUCTION);
        }
    }

    @Test
    void ar013_audit_port_is_append_only_and_credentials_have_no_custom_tostring() {
        noMethods().that().areDeclaredInClassesThat().haveFullyQualifiedName(AuditRepository.class.getName())
                .should().haveNameMatching("(?i).*(update|delete|remove|truncate).*")
                .because("AR-013: audit history exposes no mutation port")
                .check(PRODUCTION);
        noMethods().that().areDeclaredInClassesThat().haveFullyQualifiedName(UserAccount.class.getName())
                .should().haveName("toString")
                .because("AR-013: password hashes must not accidentally enter logs")
                .check(PRODUCTION);
    }
}
