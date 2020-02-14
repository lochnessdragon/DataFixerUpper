// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.datafixers;

import com.google.common.collect.Lists;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntBidirectionalIterator;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

/**
 * The class used to created a DataFixer.
 * 
 * @author Mojang
 * @version 1.0.20
 * @since 1.0.19
 * */
public class DataFixerBuilder {
    private static final Logger LOGGER = LogManager.getLogger();

    private final int dataVersion;
    private final Int2ObjectSortedMap<Schema> schemas = new Int2ObjectAVLTreeMap<>();
    private final List<DataFix> globalList = Lists.newArrayList();
    private final IntSortedSet fixerVersions = new IntAVLTreeSet();

    /**
     * Creates a DataFixerBuilder
     * 
     * @param dataVersion The highest version the fixer will be able to convert up to.
     * */
    public DataFixerBuilder(final int dataVersion) {
        this.dataVersion = dataVersion;
    }

    /**
     * Adds a schema to the list of schemas the DataFixer will be able to convert for. Use (*, CustomSchema::new).
     * 
     * @param version The version of the Schema.
     * @param factory The function used to create the Schema.
     * @return The created Schema.
     * */
    public Schema addSchema(final int version, final BiFunction<Integer, Schema, Schema> factory) {
        return addSchema(version, 0, factory);
    }
    
    /**
     * Adds a schema to the list of schemas the DataFixer will be able to convert for. Use (*, *, CustomSchema::new)
     * 
     * @param version The version of the Schema.
     * @param subVersion The sub version of the Schema.
     * @param factory The function that creates the Schema.
     * @return The created Schema.
     * */
    public Schema addSchema(final int version, final int subVersion, final BiFunction<Integer, Schema, Schema> factory) {
        final int key = DataFixUtils.makeKey(version, subVersion);
        final Schema parent = schemas.isEmpty() ? null : schemas.get(DataFixerUpper.getLowestSchemaSameVersion(schemas, key - 1));
        final Schema schema = factory.apply(DataFixUtils.makeKey(version, subVersion), parent);
        addSchema(schema);
        return schema;
    }

    /**
     * Adds a schema to the list of schemas the DataFixer will be able to convert for.
     * 
     * @param schema The Schema to add.
     * */
    public void addSchema(final Schema schema) {
        schemas.put(schema.getVersionKey(), schema);
    }

    /**
     * Adds a DataFix to the list of DataFix's for the DataFixer.
     * 
     * @param fix The DataFix to add.
     * */
    public void addFixer(final DataFix fix) {
        final int version = DataFixUtils.getVersion(fix.getVersionKey());

        if (version > dataVersion) {
            LOGGER.warn("Ignored fix registered for version: {} as the DataVersion of the game is: {}", version, dataVersion);
            return;
        }

        globalList.add(fix);
        fixerVersions.add(fix.getVersionKey());
    }

    /**
     * Builds the DataFixer. Call after all schemas and fixers have been added. (Could use: build(Executors.newSingleThreadExecutor()); )
     * 
     * @param executor A thread executor that is used to build the DataFixer. 
     * @return The DataFixer.
     * */
    public DataFixer build(final Executor executor) {
        final DataFixerUpper fixerUpper = new DataFixerUpper(new Int2ObjectAVLTreeMap<>(schemas), new ArrayList<>(globalList), new IntAVLTreeSet(fixerVersions));

        final IntBidirectionalIterator iterator = fixerUpper.fixerVersions().iterator();
        while (iterator.hasNext()) {
            final int versionKey = iterator.nextInt();
            final Schema schema = schemas.get(versionKey);
            for (final String typeName : schema.types()) {
                CompletableFuture.runAsync(() -> {
                    final Type<?> dataType = schema.getType(() -> typeName);
                    final TypeRewriteRule rule = fixerUpper.getRule(DataFixUtils.getVersion(versionKey), dataVersion);
                    dataType.rewrite(rule, DataFixerUpper.OPTIMIZATION_RULE);
                }, executor).exceptionally(e -> {
                    LOGGER.error("Unable to build datafixers", e);
                    Runtime.getRuntime().exit(1);
                    return null;
                });
            }
        }
        
        return fixerUpper;
    }
}
