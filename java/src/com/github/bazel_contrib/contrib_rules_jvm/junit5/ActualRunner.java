package com.github.bazel_contrib.contrib_rules_jvm.junit5;

import static java.nio.file.StandardOpenOption.DELETE_ON_CLOSE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.platform.launcher.EngineFilter.excludeEngines;
import static org.junit.platform.launcher.EngineFilter.includeEngines;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.engine.Constants;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherConstants;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public class ActualRunner implements RunsTest {

  @Override
  public boolean run(String testClassName) {
    String out = System.getenv("XML_OUTPUT_FILE");
    Path xmlOut;
    Path suitesDir;
    try {
      xmlOut = out != null ? Paths.get(out) : Files.createTempFile("test", ".xml");
      suitesDir = Files.createDirectories(xmlOut.getParent().resolve("suites"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    try (PrintWriter writer = new PrintWriter(System.out)) {
      CommandLineSummary summary = new CommandLineSummary();
      FailFastExtension failFastExtension = new FailFastExtension();

      LauncherConfig config =
          LauncherConfig.builder()
              .addTestExecutionListeners(
                  new LegacyXmlReportGeneratingListener(suitesDir, writer), summary, failFastExtension)
              .addPostDiscoveryFilters(TestSharding.makeShardFilter())
              .build();

      final Class<?> testClass;
      try {
        testClass = Class.forName(testClassName, false, getClass().getClassLoader());
      } catch (ClassNotFoundException e) {
        throw new RuntimeException("Failed to find testClass", e);
      }

      // We only allow for one level of nesting at the moment
      boolean enclosed = isRunWithEnclosed(testClass);
      List<DiscoverySelector> classSelectors =
          enclosed
              ? new ArrayList<>()
              : Arrays.stream(testClass.getDeclaredClasses())
                  .filter(clazz -> Modifier.isStatic(clazz.getModifiers()))
                  .map(DiscoverySelectors::selectClass)
                  .collect(Collectors.toList());

      classSelectors.add(DiscoverySelectors.selectClass(testClassName));

      LauncherDiscoveryRequestBuilder request =
          LauncherDiscoveryRequestBuilder.request()
              .selectors(classSelectors)
              .configurationParameter(LauncherConstants.CAPTURE_STDERR_PROPERTY_NAME, "true")
              .configurationParameter(LauncherConstants.CAPTURE_STDOUT_PROPERTY_NAME, "true")
              .configurationParameter(
                  Constants.EXTENSIONS_AUTODETECTION_ENABLED_PROPERTY_NAME, "true");

      String filter = System.getenv("TESTBRIDGE_TEST_ONLY");
      request.filters(new PatternFilter(filter));

      String includeTags = System.getProperty("JUNIT5_INCLUDE_TAGS");
      if (includeTags != null && !includeTags.isEmpty()) {
        request.filters(TagFilter.includeTags(includeTags.split(",")));
      }

      String excludeTags = System.getProperty("JUNIT5_EXCLUDE_TAGS");
      if (excludeTags != null && !excludeTags.isEmpty()) {
        request.filters(TagFilter.excludeTags(excludeTags.split(",")));
      }

      List<String> includeEngines =
          System.getProperty("JUNIT5_INCLUDE_ENGINES") == null
              ? null
              : Arrays.asList(System.getProperty("JUNIT5_INCLUDE_ENGINES").split(","));
      List<String> excludeEngines =
          System.getProperty("JUNIT5_EXCLUDE_ENGINES") == null
              ? null
              : Arrays.asList(System.getProperty("JUNIT5_EXCLUDE_ENGINES").split(","));
      if (includeEngines != null) {
        request.filters(includeEngines(includeEngines));
      }
      if (excludeEngines != null) {
        request.filters(excludeEngines(excludeEngines));
      }

      File exitFile = getExitFile();

      Launcher launcher = LauncherFactory.create(config);
      launcher.execute(request.build());
      writeAggregatedReport(suitesDir.toFile(), xmlOut.toFile());

      deleteExitFile(exitFile);

      summary.writeTo(writer);
      return summary.getFailureCount() == 0;
    }
  }

  private static void writeAggregatedReport(File suitesDir, File xmlOut) {
    try {
      DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      Document document = documentBuilder.newDocument();
      Element suites = document.createElement("testsuites");
      for (File suite : suitesDir.listFiles()) {
        suites.appendChild(document.adoptNode(documentBuilder.parse(suite).getDocumentElement()));
      }
      document.appendChild(suites);
      TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(xmlOut));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ParserConfigurationException | SAXException | TransformerException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Checks if the test class is annotation with `@RunWith(Enclosed.class)`. We deliberately avoid
   * using types here to avoid polluting the classpath with junit4 deps.
   */
  private boolean isRunWithEnclosed(Class<?> clazz) {
    for (Annotation annotation : clazz.getAnnotations()) {
      Class<? extends Annotation> type = annotation.annotationType();
      if (type.getName().equals("org.junit.runner.RunWith")) {
        try {
          Class<?> runner = (Class<?>) type.getMethod("value").invoke(annotation, (Object[]) null);
          if (runner.getName().equals("org.junit.experimental.runners.Enclosed")) {
            return true;
          }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
          return false;
        }
      }
    }
    return false;
  }

  private File getExitFile() {
    String exitFileName = System.getenv("TEST_PREMATURE_EXIT_FILE");
    if (exitFileName == null) {
      return null;
    }

    File exitFile = new File(exitFileName);
    try {
      Files.write(exitFile.toPath(), "".getBytes(), WRITE, DELETE_ON_CLOSE, TRUNCATE_EXISTING);
    } catch (IOException e) {
      return null;
    }

    return exitFile;
  }

  private void deleteExitFile(File exitFile) {
    if (exitFile != null) {
      try {
        exitFile.delete();
      } catch (Throwable t) {
        // Ignore.
      }
    }
  }
}
