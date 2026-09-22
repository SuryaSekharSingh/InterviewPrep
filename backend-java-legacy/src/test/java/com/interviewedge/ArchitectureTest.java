package com.interviewedge;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  @Test
  void businessModulesDoNotImportProviderImplementations() {
    noClasses()
        .that()
        .resideInAnyPackage("..tests..", "..interviews..", "..english..", "..progress..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..infrastructure..")
        .check(new ClassFileImporter().importPackages("com.interviewedge"));
  }
}
