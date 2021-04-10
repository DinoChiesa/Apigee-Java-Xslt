// Copyright 2017-2021 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.apigee.callouts.xslt;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.message.Message;
import com.apigee.flow.message.MessageContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import mockit.Mock;
import mockit.MockUp;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.Diff;

public class TestXsltCallout {
  private static final String testDataDir = "src/test/resources/test-data";

  MessageContext msgCtxt;
  // String messageContent;
  InputStream messageContentStream;
  Message message;
  ExecutionContext exeCtxt;

  @BeforeMethod()
  public void beforeMethod() {

    msgCtxt =
        new MockUp<MessageContext>() {
          private Map variables;

          public void $init() {
            variables = new HashMap();
          }

          @Mock()
          public <T> T getVariable(final String name) {
            if (variables == null) {
              variables = new HashMap();
            }
            return (T) variables.get(name);
          }

          @Mock()
          public boolean setVariable(final String name, final Object value) {
            if (variables == null) {
              variables = new HashMap();
            }
            variables.put(name, value);
            return true;
          }

          @Mock()
          public boolean removeVariable(final String name) {
            if (variables == null) {
              variables = new HashMap();
            }
            if (variables.containsKey(name)) {
              variables.remove(name);
            }
            return true;
          }

          @Mock()
          public Message getMessage() {
            return message;
          }
        }.getMockInstance();

    exeCtxt = new MockUp<ExecutionContext>() {}.getMockInstance();

    message =
        new MockUp<Message>() {
          @Mock()
          public InputStream getContentAsStream() {
            // new ByteArrayInputStream(messageContent.getBytes(StandardCharsets.UTF_8));
            return messageContentStream;
          }
        }.getMockInstance();
  }

  @DataProvider(name = "batch1")
  public static Object[][] getDataForBatch1() throws IOException, IllegalStateException {

    // @DataProvider requires the output to be a Object[][]. The inner
    // Object[] is the set of params that get passed to the test method.
    // So, if you want to pass just one param to the constructor, then
    // each inner Object[] must have length 1.

    ObjectMapper om = new ObjectMapper();
    om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Path currentRelativePath = Paths.get("");
    // String s = currentRelativePath.toAbsolutePath().toString();
    // System.out.println("Current relative path is: " + s);

    // read in all the *.json files in the test-data directory
    File testDir = new File(testDataDir);
    if (!testDir.exists()) {
      throw new IllegalStateException("no test directory.");
    }
    File[] files = testDir.listFiles();
    if (files.length == 0) {
      throw new IllegalStateException("no tests found.");
    }
    Arrays.sort(files);
    int c = 0;
    ArrayList<TestCase> list = new ArrayList<TestCase>();
    for (File file : files) {
      String name = file.getName();
      if (name.matches("^[0-9]+.+.json$")) {
        TestCase tc = om.readValue(file, TestCase.class);
        tc.setTestName(name.substring(0, name.length() - 5));
        list.add(tc);
      }
    }
    return list.stream().map(tc -> new TestCase[] {tc}).toArray(Object[][]::new);
  }

  @Test
  public void testDataProviders() throws IOException {
    Assert.assertTrue(getDataForBatch1().length > 0);
  }

  private static String resolveFileReference(String ref) throws IOException {
    return new String(Files.readAllBytes(Paths.get(testDataDir, ref.substring(7, ref.length()))));
  }

  private InputStream getInputStream(TestCase tc) throws Exception {
    if (tc.getInput() != null) {
      Path path = Paths.get(testDataDir, tc.getInput());
      if (!Files.exists(path)) {
        throw new IOException("file(" + tc.getInput() + ") not found");
      }
      return Files.newInputStream(path);
    }

    // readable empty stream
    return new ByteArrayInputStream(new byte[] {});
  }

  @Test(dataProvider = "batch1")
  public void test2_Configs(TestCase tc) throws Exception {
    if (tc.getDescription() != null)
      System.out.printf("  %10s - %s\n", tc.getTestName(), tc.getDescription());
    else System.out.printf("  %10s\n", tc.getTestName());

    // set variables into message context
    for (Map.Entry<String, String> entry : tc.getContext().entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (value.startsWith("file://")) {
        value = resolveFileReference(value);
      }
      msgCtxt.setVariable(key, value);
    }

    messageContentStream = getInputStream(tc);

    XsltCallout callout = new XsltCallout(tc.getProperties());

    // execute and retrieve output
    ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);

    String s = tc.getExpected().get("success");
    ExecutionResult expectedResult =
        (s != null && s.toLowerCase().equals("true"))
            ? ExecutionResult.SUCCESS
            : ExecutionResult.ABORT;
    // check result and output
    if (expectedResult == actualResult) {
      if (expectedResult == ExecutionResult.SUCCESS) {
        String fname = tc.getExpected().get("output");
        Path path = Paths.get(testDataDir, fname);
        if (!Files.exists(path)) {
          throw new IOException("expected output file(" + fname + ") not found");
        }
        InputStream in = Files.newInputStream(path);
        String expectedOutput =
                      new BufferedReader(new InputStreamReader(in))
                            .lines().collect(Collectors.joining("\n"));

        String actualOutput = (String) (msgCtxt.getVariable("message.content"));

        Diff diff =
            DiffBuilder.compare(expectedOutput)
                .withTest(actualOutput)
                .ignoreComments()
                .ignoreWhitespace()
                .checkForSimilar()
                .build();

        if (diff.hasDifferences()) {
          System.err.printf("    got     : %s\n", actualOutput);
          System.err.printf("    expected: %s\n", expectedOutput);
        }
        Assert.assertFalse(diff.hasDifferences());
      } else {
        String expectedError = tc.getExpected().get("error");
        Assert.assertNotNull(expectedError, "broken test: no expected error specified");
        String actualError = msgCtxt.getVariable("xslt_error");
        Assert.assertEquals(actualError, expectedError, tc.getTestName() + " error");
      }
    } else {
      String observedError = msgCtxt.getVariable("xslt_error");
      System.err.printf("    observed error: %s\n", observedError);
      Pattern p = Pattern.compile("^Encountered ([1-9][0-9]*) errors while transforming$");
      Matcher m = p.matcher(observedError);
      if (m.find()) {
        int errorCount = Integer.parseInt(m.group(1));
        for (int i = 0; i < errorCount; i++) {
          System.err.printf("    error: %s\n", msgCtxt.getVariable("xslt_error_" + (i + 1)));
        }
      }

      Assert.assertEquals(actualResult, expectedResult, "result not as expected");
    }
    System.out.println("=========================================================");
  }
}
