// Copyright (c) 2015 K Team. All Rights Reserved.
package org.kframework.backend.java.symbolic;

import org.kframework.definition.Definition;
import org.kframework.definition.DefinitionTransformer;
import org.kframework.definition.Module;
import org.kframework.definition.ModuleTransformer;
import org.kframework.kompile.CompiledDefinition;
import org.kframework.kompile.Kompile;
import org.kframework.kore.KORE;
import org.kframework.kore.compile.Backend;
import org.kframework.kore.compile.ConvertDataStructureToLookup;
import org.kframework.kore.compile.MergeRules;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Created by dwightguth on 9/1/15.
 */
public class JavaBackend implements Backend {

    @Override
    public void accept(CompiledDefinition def) {
    }

    @Override
    public Function<Definition, Definition> steps(Kompile kompile) {
        DefinitionTransformer convertDataStructureToLookup = DefinitionTransformer.from(
                m -> ModuleTransformer.fromSentenceTransformer(new ConvertDataStructureToLookup(m, true)::convert, "convert data structures to lookups").apply(m),
                "convert data structures to lookups");
        return d -> convertDataStructureToLookup.andThen(new DefinitionTransformer(new MergeRules(KORE.c()))).apply(kompile.defaultSteps().apply(d));
    }
}