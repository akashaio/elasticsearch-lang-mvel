/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.script.mvel;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Scorer;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.math.UnboxedMathUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.LeafSearchScript;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.lookup.LeafSearchLookup;
import org.elasticsearch.search.lookup.SearchLookup;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;
import org.mvel2.compiler.ExecutableStatement;
import org.mvel2.integration.impl.MapVariableResolverFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class MvelScriptEngineService extends AbstractComponent implements ScriptEngineService {

    private final ParserConfiguration parserConfiguration;

    @Inject
    public MvelScriptEngineService(Settings settings) {
        super(settings);

        parserConfiguration = new ParserConfiguration();
        parserConfiguration.addPackageImport("java.util");
        parserConfiguration.addPackageImport("gnu.trove");
        parserConfiguration.addPackageImport("org.joda");
        parserConfiguration.addImport("time", MVEL.getStaticMethod(System.class, "currentTimeMillis", new Class[0]));
        // unboxed version of Math, better performance since conversion from boxed to unboxed my mvel is not needed
        for (Method m : UnboxedMathUtils.class.getMethods()) {
            if ((m.getModifiers() & Modifier.STATIC) > 0) {
                parserConfiguration.addImport(m.getName(), m);
            }
        }
    }

    @Override
    public String[] types() {
        return new String[] { "mvel" };
    }

    @Override
    public String[] extensions() {
        return new String[] { "mvel" };
    }

    @Override
    public boolean sandboxed() {
        return false;
    }

    @Override
    public Object compile(String script) {
        return MVEL.compileExpression(script, new ParserContext(parserConfiguration));
    }

    @Override
    public ExecutableScript executable(Object compiledScript, @Nullable Map<String, Object> vars) {
        return new MvelExecutableScript(compiledScript, vars);
    }

    @Override
    public SearchScript search(final Object compiledScript, final SearchLookup lookup, @Nullable final Map<String, Object> vars) {
        return new SearchScript() {

            @Override
            public LeafSearchScript getLeafSearchScript(LeafReaderContext context) throws IOException {
                final LeafSearchLookup leafLookup = lookup.getLeafSearchLookup(context);
                return new MvelSearchScript(compiledScript, leafLookup, vars);
            }
        };
    }

    @Override
    public Object execute(Object compiledScript, Map<String, Object> vars) {
        return MVEL.executeExpression(compiledScript, vars);
    }

    @Override
    public Object unwrap(Object value) {
        return value;
    }

    @Override
    public void close() {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void scriptRemoved(@Nullable CompiledScript compiledScript) {

    }

    public static class MvelExecutableScript implements ExecutableScript {

        private final ExecutableStatement script;

        private final MapVariableResolverFactory resolver;

        public MvelExecutableScript(Object script, Map vars) {
            this.script = (ExecutableStatement) script;
            if (vars != null) {
                this.resolver = new MapVariableResolverFactory(vars);
            } else {
                this.resolver = new MapVariableResolverFactory(new HashMap());
            }
        }

        @Override
        public void setNextVar(String name, Object value) {
            resolver.createVariable(name, value);
        }

        @Override
        public Object run() {
            return script.getValue(null, resolver);
        }

        @Override
        public Object unwrap(Object value) {
            return value;
        }
    }

    public static class MvelSearchScript implements LeafSearchScript {

        private final ExecutableStatement script;

        private final LeafSearchLookup lookup;

        private final MapVariableResolverFactory resolver;

        private Scorer scorer;

        public MvelSearchScript(Object script, LeafSearchLookup lookup, Map<String, Object> vars) {
            this.script = (ExecutableStatement) script;
            this.lookup = lookup;
            if (vars != null) {
                this.resolver = new MapVariableResolverFactory(vars);
            } else {
                this.resolver = new MapVariableResolverFactory(new HashMap());
            }
            for (Map.Entry<String, Object> entry : lookup.asMap().entrySet()) {
                resolver.createVariable(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void setScorer(Scorer scorer) {
            this.scorer = scorer;
        }

        @Override
        public void setDocument(int doc) {
            lookup.setDocument(doc);
        }

        @Override
        public void setNextVar(String name, Object value) {
            resolver.createVariable(name, value);
        }

        @Override
        public void setSource(Map<String, Object> source) {
            lookup.source().setSource(source);
        }

        @Override
        public Object run() {
            try {
                if (scorer != null) {
                    resolver.createVariable("_score", scorer.score());
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not get score", e);
            }
            return script.getValue(null, resolver);
        }

        @Override
        public float runAsFloat() {
            return ((Number) run()).floatValue();
        }

        @Override
        public long runAsLong() {
            return ((Number) run()).longValue();
        }

        @Override
        public double runAsDouble() {
            return ((Number) run()).doubleValue();
        }

        @Override
        public Object unwrap(Object value) {
            return value;
        }
    }
}
