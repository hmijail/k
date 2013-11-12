package org.kframework.ktest2.Test;

import org.apache.commons.io.IOUtils;
import org.kframework.krun.ColorSetting;
import org.kframework.ktest2.DefaultStringComparator;
import org.kframework.ktest2.KTestStep;
import org.kframework.ktest2.PgmArg;
import org.kframework.ktest2.Proc;
import org.kframework.utils.ColorUtil;

import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class TestSuite {

    /**
     * List of test cases. It's assumed that every definition can have at most one test case.
     * (no two <test ...> ... </test> elements share same `definition' attribute)
     * TODO: maybe check this and throw a warning? (osa1)
     */
    private final List<TestCase> tests;

    private ThreadPoolExecutor tpe;

    private final boolean verbose;

    private final ColorSetting colorSetting;

    /**
     * List of ktest steps to skip. This array should be sorted because it'll be used for binary
     * search (Java doesn't have linear search algorithm in stdlib)
     */
    private final KTestStep[] skips;

    /**
     * Timeout for a process.
     */
    private final int timeout;

    public TestSuite(List<TestCase> tests, KTestStep[] skips, boolean verbose,
                     ColorSetting colorSetting, int timeout) {
        this.tests = tests;
        this.skips = skips;
        this.verbose = verbose;
        this.colorSetting = colorSetting;
        this.timeout = timeout;
    }

    public TestSuite(TestCase singleTest, KTestStep[] skips, boolean verbose,
                     ColorSetting colorSetting, int timeout) {
        tests = new LinkedList<>();
        tests.add(singleTest);
        this.skips = skips;
        this.verbose = verbose;
        this.colorSetting = colorSetting;
        this.timeout = timeout;
    }

    /**
     * Run TestSuite and return true if all tests passed.
     * @return whether all tests passed or not
     * @throws InterruptedException when some process is interrupted for some reason
     */
    public boolean run() throws InterruptedException {
        boolean ret = true;
        List<TestCase> successfulTests = tests;

        if (Arrays.binarySearch(skips, KTestStep.KOMPILE) < 0) {
            successfulTests = runKompileSteps(tests);
            ret &= successfulTests.size() == tests.size();
        }
        if (Arrays.binarySearch(skips, KTestStep.PDF) < 0)
            ret &= runPDFSteps(successfulTests);
        if (Arrays.binarySearch(skips, KTestStep.KRUN) < 0)
            ret &= runKRunSteps(successfulTests);
        return ret;
    }

    /**
     * Run kompile steps in list of test cases.
     *
     * This method returns something different from others, this is because in kompile tests we
     * need to know exactly what tests passed, because otherwise we can't know what krun and
     * pdf tests to run (running krun/pdf on a broken definition doesn't make sense)
     * @return list of test cases that run successfully
     * @throws InterruptedException
     */
    private List<TestCase> runKompileSteps(List<TestCase> tests) throws InterruptedException {
        int len = tests.size();
        List<TestCase> successfulTests = new ArrayList<>(len);
        List<Proc<TestCase>> ps = new ArrayList<>(len);

        System.out.format("Kompile the language definitions...(%d in total)%n", len);
        startTpe();
        for (TestCase tc : tests) {
            String definitionPath = tc.getDefinition();
            assert new File(definitionPath).isFile();
            // build argument array
            List<PgmArg> kompileOpts = tc.getKompileOpts();
            String[] args = new String[kompileOpts.size() + 2];
            args[0] = "kompile";
            args[1] = definitionPath;
            for (int i = 0; i < kompileOpts.size(); i++)
                args[i+2] = kompileOpts.get(i).toString();
            // execute
            Proc<TestCase> p = new Proc<>(tc, args, timeout, verbose, colorSetting);
            ps.add(p);
            tpe.execute(p);
        }
        stopTpe();

        // collect successful test cases
        for (Proc<TestCase> p : ps)
            if (p.getReturnCode() == 0)
                successfulTests.add(p.getObj());

        printResult(successfulTests.size() == len);

        return successfulTests;
    }

    /**
     * Run pdf tests.
     * @param tests list of tests to run pdf step
     * @return whether all run successfully or not
     * @throws InterruptedException
     */
    private boolean runPDFSteps(List<TestCase> tests) throws InterruptedException {
        List<Proc<TestCase>> ps = new ArrayList<>();
        int len = tests.size();
        System.out.format("Generate PDF files...(%d in total)%n", len);
        startTpe();
        for (TestCase tc : tests) {
            String definitionPath = tc.getDefinition();
            assert new File(definitionPath).isFile();
            Proc<TestCase> p = new Proc<>(tc, new String[] { "kompile", "--pdf", definitionPath },
                    timeout, verbose, colorSetting);
            ps.add(p);
            tpe.execute(p);
        }
        stopTpe();

        boolean ret = true;
        for (Proc<TestCase> p : ps)
            ret &= p.getReturnCode() == 0;

        printResult(ret);

        return ret;
    }

    /**
     * Run krun tests.
     * @param tests list of test cases to run krun steps
     * @return whether all run successfully or not
     * @throws InterruptedException
     */
    private boolean runKRunSteps(List<TestCase> tests) throws InterruptedException {
        List<TestCase> successfulTests = new LinkedList<>();

        // collect definitions that are not yet kompiled and kompile them first
        ArrayList<TestCase> notKompiled = new ArrayList<>();
        for (TestCase tc : tests) {
            if (!tc.isDefinitionKompiled())
                notKompiled.add(tc);
            else
                successfulTests.add(tc);
        }
        System.out.println("Kompiling definitions that are not yet kompiled.");
        successfulTests.addAll(runKompileSteps(notKompiled));

        // at this point we have a subset of tests that are successfully kompiled,
        // so run programs of those tests
        for (TestCase tc : successfulTests) {

            List<KRunProgram> programs = tc.getPrograms();
            int inputs = 0, outputs = 0, errors = 0;
            for (KRunProgram p : programs) {
                if (p.inputFile != null) inputs++;
                if (p.outputFile != null) outputs++;
                if (p.errorFile != null) errors++;
            }

            System.out.format("Running %s programs... (%d in total, %d with input, " +
                    "%d with output, %d with error)%n", tc.getDefinition(), programs.size(),
                    inputs, outputs, errors);

            // we can have more parallelism here, but just to keep things same as old ktest,
            // I'm testing tast cases sequentially
            List<Proc<KRunProgram>> testCaseProcs = new ArrayList<>(programs.size());
            startTpe();
            for (KRunProgram program : programs)
                testCaseProcs.add(runKRun(program));
            stopTpe();

            boolean testCaseRet = true;
            for (Proc<KRunProgram> p : testCaseProcs)
                if (p != null) // p may be null when krun test is skipped because of missing
                               // input file
                    testCaseRet &= p.getReturnCode() == 0;

            printResult(testCaseRet);
        }

        return successfulTests.size() == tests.size();
    }

    private void startTpe() {
        tpe = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors());
    }

    private void stopTpe() throws InterruptedException {
        tpe.shutdown();
        while (!tpe.awaitTermination(1, TimeUnit.SECONDS));
    }

    /**
     * Execute krun step of a program.
     * @param program
     */
    private Proc<KRunProgram> runKRun(KRunProgram program) {
        String[] args = new String[program.args.size() + 1];
        args[0] = "krun";
        for (int i = 1; i < args.length; i++)
            args[i] = program.args.get(i - 1);

        // passing null to Proc is OK, it means `ignore'
        String inputContents = null, outputContents = null, errorContents = null;
        if (program.inputFile != null)
            try {
                inputContents = IOUtils.toString(new FileInputStream(new File(program.inputFile)));
            } catch (IOException e) {
                System.out.format("WARNING: cannot read input file %s -- skipping program %s%n",
                        program.inputFile, program.args.get(1));
                // this case happens when an input file is found by TestCase,
                // but somehow file is not readable. in that case there's no point in running the
                // program because it'll wait for input forever.
                return null;
            }
        if (program.outputFile != null)
            try {
                outputContents = IOUtils.toString(new FileInputStream(
                        new File(program.outputFile)));
            } catch (IOException e) {
                System.out.format("WARNING: cannot read output file %s -- program output " +
                        "won't be matched against output file%n", program.outputFile);
            }
        if (program.errorFile != null)
            try {
                errorContents = IOUtils.toString(new FileInputStream(
                        new File(program.errorFile)));
            } catch (IOException e) {
                System.out.format("WARNING: cannot read error file %s -- program error output "
                        + "won't be matched against error file%n", program.errorFile);
            }

        // TODO: maybe enable this only when in verbose mode, othewise output is becoming just
        // too verbose
        /*
        String procCmd = StringUtils.join(args, ' ');
        System.out.format("Running %s [ %s]%n", procCmd,
                (inputContents == null ? "" : "input ")
                + (outputContents == null ? "" : "output ")
                + (errorContents == null ? "" : "error "));
        */
        Proc<KRunProgram> p = new Proc<>(program, args, inputContents, outputContents,
                errorContents, new DefaultStringComparator(), timeout, verbose, colorSetting);
        tpe.execute(p);
        return p;
    }

    private void printResult(boolean condition) {
        if (condition)
            System.out.println("SUCCESS");
        else
            System.out.println(ColorUtil.RgbToAnsi(Color.red, colorSetting) + "FAIL");
    }
}