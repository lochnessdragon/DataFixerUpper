// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.datafixers.schemas;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.types.families.RecursiveTypeFamily;
import com.mojang.datafixers.types.families.TypeFamily;
import com.mojang.datafixers.types.templates.RecursivePoint;
import com.mojang.datafixers.types.templates.TaggedChoice;
import com.mojang.datafixers.types.templates.TypeTemplate;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;



/**
 * A class that represents a bunch of data types.                                               
 * 
 * Do not directly create a new Schema, you need to extend the schema with your own custom one. 
 * Then, add your types in the appropriate methods in the child class.
 * 
 * @author Mojang
 * @version 1.0.20
 * @since 1.0.19
 */
public class Schema {
    protected final Object2IntMap<String> RECURSIVE_TYPES = new Object2IntOpenHashMap<>();
    private final Map<String, Supplier<TypeTemplate>> TYPE_TEMPLATES = Maps.newHashMap();
    private final Map<String, Type<?>> TYPES;
    private final int versionKey;
    private final String name;
    protected final Schema parent;
    
    /**
     * Creates a Schema for a given version with a parent, if the Schema has one.
     * 
     * @param versionKey The version key of this schema, created with DataFixUtils.makeKey(int version);
     * @param parent Used if your schema adds on to another schema. 
     * */
    public Schema(final int versionKey, final Schema parent) {
        this.versionKey = versionKey;
        final int subVersion = DataFixUtils.getSubVersion(versionKey);
        name = "V" + DataFixUtils.getVersion(versionKey) + (subVersion == 0 ? "" : "." + subVersion);
        this.parent = parent;
        registerTypes(this, registerEntities(this), registerBlockEntities(this));
        TYPES = buildTypes();
    }
    
    /**
     * Adds all of the types together to get a map of all types used.
     * 
     * @return The collective types used.
     * */
    protected Map<String, Type<?>> buildTypes() {
        final Map<String, Type<?>> types = Maps.newHashMap();

        final List<TypeTemplate> templates = Lists.newArrayList();

        for (final Object2IntMap.Entry<String> entry : RECURSIVE_TYPES.object2IntEntrySet()) {
            templates.add(DSL.check(entry.getKey(), entry.getIntValue(), getTemplate(entry.getKey())));
        }

        final TypeTemplate choice = templates.stream().reduce(DSL::or).get();
        final TypeFamily family = new RecursiveTypeFamily(name, choice);

        for (final String name : TYPE_TEMPLATES.keySet()) {
            final Type<?> type;
            final int recurseId = RECURSIVE_TYPES.getOrDefault(name, -1);
            if (recurseId != -1) {
                type = family.apply(recurseId);
            } else {
                type = getTemplate(name).apply(family).apply(-1);
            }
            types.put(name, type);
        }
        return types;
    }
    
    /**
     * Gets all of the type names used by this Schema. 
     * 
     * @return All of the type names used in a Set&lt;String&gt;
     * */
    public Set<String> types() {
        return TYPES.keySet();
    }
    
    /**
     * Returns the type for a given reference. If it is unable to do so, it throws an IllegalArgumentException.
     * 
     * @throws IllegalArgumentException
     * @param type The TypeReference used to find the type.
     * @return The type for the given TypeReference.
     * */
    public Type<?> getTypeRaw(final DSL.TypeReference type) {
        final String name = type.typeName();
        return TYPES.computeIfAbsent(name, key -> {
            throw new IllegalArgumentException("Unknown type: " + name);
        });
    }

    /**
     * Returns the type for a given reference. If it is unable to do so, it throws an IllegalArgumentException. If the type is recursive,
     * then it returns the checked type. If it can't, then it throws an IllegalStateException
     * 
     * @throws IllegalArgumentException
     * @throws IllegalStateException
     * @param type The TypeReference used to find the type.
     * @return The type for the given TypeReference.
     * */
    public Type<?> getType(final DSL.TypeReference type) {
        final String name = type.typeName();
        final Type<?> type1 = TYPES.computeIfAbsent(name, key -> {
            throw new IllegalArgumentException("Unknown type: " + name);
        });
        if (type1 instanceof RecursivePoint.RecursivePointType<?>) {
            return type1.findCheckedType(-1).orElseThrow(() -> new IllegalStateException("Could not find choice type in the recursive type"));
        }
        return type1;
    }

    /**
     * Tries to find the TypeTemplate based on the string it returns. It does not work for recursive types. If it cannot find one, it throws an IllegalArgumentException.
     * 
     * @throws IllegalArgumentException
     * @param name The string the TypeTemplate returns
     * @return The TypeTemplate if it finds one.
     * */
    public TypeTemplate resolveTemplate(final String name) {
        return TYPE_TEMPLATES.getOrDefault(name, () -> {
            throw new IllegalArgumentException("Unknown type: " + name);
        }).get();
    }

    /**
     * Gets the TypeTemplate for a given name. It works with regular and recursive types.
     * 
     *  @param name The name of the recursive TypeTemplate
     *  @return The TypeTemplate it found for the name.
     * */
    public TypeTemplate id(final String name) {
        final int id = RECURSIVE_TYPES.getOrDefault(name, -1);
        if (id != -1) {
            return DSL.id(id);
        }
        return getTemplate(name);
    }

    protected TypeTemplate getTemplate(final String name) {
        return DSL.named(name, resolveTemplate(name));
    }

    /**
     * Gets a type in a ChoiceType class if it exists. If not, it throws an IllegalArgumentException.
     * 
     * @throws IllegalArgumentException
     * @param type The TypeReference to search for.
     * @param choiceName The name of the choice.
     * @return The Type for the given TypeReference.
     * */
    public Type<?> getChoiceType(final DSL.TypeReference type, final String choiceName) {
        final TaggedChoice.TaggedChoiceType<?> choiceType = findChoiceType(type);
        if (!choiceType.types().containsKey(choiceName)) {
            throw new IllegalArgumentException("Data fixer not registered for: " + choiceName + " in " + type.typeName());
        }
        return choiceType.types().get(choiceName);
    }

    /**
     * Gets a choice type for a TypeReference if it exists. If not it throws an IllegalArgumentException.
     * 
     * @throws IllegalArgumentException
     * @param type The TypeReference to search for.
     * @return The TaggedChoiceType it got, if it found one.
     * */
    public TaggedChoice.TaggedChoiceType<?> findChoiceType(final DSL.TypeReference type) {
        return getType(type).findChoiceType("id", -1).orElseThrow(() -> new IllegalArgumentException("Not a choice type"));
    }

    /**
     * Registers all the types for this schema. Called in buildTypes in object construction.
     * @param schema The Schema class to register the types under.
     * @param entityTypes The map of entity types. In the constructor, they come from the registerEntities() function. (Minecraft remnants)
     * @param blockEntityTypes The map of block entity types. In the constructor, they come from the registerBlockEntites() function. (Minecraft remnants)
     * */
    public void registerTypes(final Schema schema, final Map<String, Supplier<TypeTemplate>> entityTypes, final Map<String, Supplier<TypeTemplate>> blockEntityTypes) {
        parent.registerTypes(schema, entityTypes, blockEntityTypes);
    }

    /**
     * Remnants of the Minecraft codebase, hopefully a better implementation is coming
     * @param schema The schema that the TypeTemplates are registered with
     * @return The map of registered TypeTemplates
     * */
    public Map<String, Supplier<TypeTemplate>> registerEntities(final Schema schema) {
        return parent.registerEntities(schema);
    }
    
    /**
     * Remnants of the Minecraft codebase, hopefully a better implementation is coming
     * @param schema The schema that the TypeTemplates are registered with
     * @return The map of registered TypeTemplates
     * */
    public Map<String, Supplier<TypeTemplate>> registerBlockEntities(final Schema schema) {
        return parent.registerBlockEntities(schema);
    }

    /**
     * Used in the Minecraft codebase for registerBlockEntities and registerEntities functions. 
     * 
     * Registers a TypeTemplate from the Supplier DSL::remainder for a set name and map. 
     * @param map The map to add the TypeTemplate to.
     * @param name The name of the TypeTemplate. 
     * */
    public void registerSimple(final Map<String, Supplier<TypeTemplate>> map, final String name) {
        register(map, name, DSL::remainder);
    }

    /**
     * Used in the Minecraft codebase for registerBlockEntities and registerEntities functions. 
     * 
     * Registers a TypeTemplate for a set name and map. 
     * @param map The map to add the TypeTemplate to.
     * @param name The name of the TypeTemplate. 
     * @param template The Function that creates the TypeTemplate to add.
     * */
    public void register(final Map<String, Supplier<TypeTemplate>> map, final String name, final Function<String, TypeTemplate> template) {
        register(map, name, () -> template.apply(name));
    }

    /**
     * Used in the Minecraft codebase for registerBlockEntities and registerEntities functions. 
     * 
     * Registers a TypeTemplate for a set name and map. 
     * @param map The map to add the TypeTemplate to.
     * @param name The name of the TypeTemplate. 
     * @param template The Supplier that creates the TypeTemplate to add.
     */
    public void register(final Map<String, Supplier<TypeTemplate>> map, final String name, final Supplier<TypeTemplate> template) {
        map.put(name, template);
    }

    /**
     * Registers a type for use in the Schema. 
     * 
     * @param recursive Whether the type recurses.
     * @param type The type that your registering.
     * @param template The TypeTemplate that is the structure of the certain type.
     * */
    public void registerType(final boolean recursive, final DSL.TypeReference type, final Supplier<TypeTemplate> template) {
        TYPE_TEMPLATES.put(type.typeName(), template);
        // TODO: calculate recursiveness instead of hardcoding
        if (recursive && !RECURSIVE_TYPES.containsKey(type.typeName())) {
            RECURSIVE_TYPES.put(type.typeName(), RECURSIVE_TYPES.size());
        }
    }

    /**
     * @return The version key of the schema.
     * */
    public int getVersionKey() {
        return versionKey;
    }

    /**
     * @return The schema's parent.
     * */
    public Schema getParent() {
        return parent;
    }
}
