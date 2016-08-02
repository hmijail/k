// Copyright (c) 2016 K Team. All Rights Reserved.
package org.kframework;

import com.google.inject.Provider;
import org.kframework.RewriterResult;
import org.kframework.attributes.Source;
import org.kframework.backend.java.symbolic.InitializeRewriter;
import org.kframework.backend.java.symbolic.JavaExecutionOptions;
import org.kframework.definition.Definition;
import org.kframework.kompile.CompiledDefinition;
import org.kframework.kompile.Kompile;
import org.kframework.kompile.KompileOptions;
import org.kframework.kore.K;
import org.kframework.kore.compile.KTokenVariablesToTrueVariables;
import org.kframework.krun.KRun;
import org.kframework.krun.KRunOptions;
import org.kframework.krun.api.io.FileSystem;
import org.kframework.krun.ioserver.filesystem.portable.PortableFileSystem;
import org.kframework.main.GlobalOptions;
import org.kframework.rewriter.Rewriter;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.file.FileUtil;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.kframework.Collections.*;
import static org.kframework.kore.KORE.*;

/**
 * KRunAPI
 */
public class KRunAPI {

    public static RewriterResult run(CompiledDefinition compiledDef, String programText, Integer depth) {

        GlobalOptions globalOptions = new GlobalOptions();
        KompileOptions kompileOptions = new KompileOptions();
        KRunOptions krunOptions = new KRunOptions();
        JavaExecutionOptions javaExecutionOptions = new JavaExecutionOptions();

        KExceptionManager kem = new KExceptionManager(globalOptions);
        FileUtil files = FileUtil.testFileUtil();
        boolean ttyStdin = false;

        FileSystem fs = new PortableFileSystem(kem, files);
        Map<String, Provider<MethodHandle>> hookProvider = HookProvider.get(kem); // new HashMap<>();
        InitializeRewriter.InitializeDefinition initializeDefinition = new InitializeRewriter.InitializeDefinition();

        BiFunction<String, Source, K> programParser = compiledDef.getProgramParser(kem);
        K pgm = programParser.apply(programText, Source.apply("generated by api"));
        K program = KRun.parseConfigVars(krunOptions, compiledDef, kem, files, ttyStdin, pgm);

        /* TODO: figure out if it is needed
        program = new KTokenVariablesToTrueVariables()
                .apply(compiledDef.kompiledDefinition.getModule(compiledDef.mainSyntaxModuleName()).get(), program);
         */

        Rewriter rewriter = (InitializeRewriter.SymbolicRewriterGlue)
            new InitializeRewriter(
                fs,
                javaExecutionOptions,
                globalOptions,
                kem,
                kompileOptions.experimental.smt,
                hookProvider,
                kompileOptions,
                krunOptions,
                files,
                initializeDefinition)
            .apply(compiledDef.executionModule());

        RewriterResult result = ((InitializeRewriter.SymbolicRewriterGlue) rewriter).execute(program, Optional.ofNullable(depth), true);

        return result;
    }

    public static void main(String[] args) {
        GlobalOptions globalOptions = new GlobalOptions();
        KompileOptions kompileOptions = new KompileOptions();
        KRunOptions krunOptions = new KRunOptions();
        KExceptionManager kem = new KExceptionManager(globalOptions);
        FileUtil files = FileUtil.testFileUtil();

        if (args.length < 2) {
            System.out.println("usage: <def> <main-module> <pgm>");
            return;
        }
        String def = FileUtil.load(new File(args[0])); // "require \"domains.k\" module A syntax KItem ::= \"run\" endmodule"
        String pgm = FileUtil.load(new File(args[2])); // "run"

        String mainModuleName = args[1]; // "A"

        // kompile
        Definition d = DefinitionParser.from(def, mainModuleName);
        CompiledDefinition compiledDef = Kompile.run(d, kompileOptions, kem);

        // krun
        RewriterResult result = run(compiledDef, pgm, null);

        // print output
        // from org.kframework.krun.KRun.run()
        KRun.prettyPrint(compiledDef, krunOptions.output, s -> KRun.outputFile(s, krunOptions, files), result.k());

        return;
    }

}