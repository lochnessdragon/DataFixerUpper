// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.datafixers;

import com.mojang.datafixers.schemas.Schema;

/**
 * The interface that provides the methods for a DataFixer
 * 
 * @author Mojang
 * @version 1.0.20
 * @since 1.0.19
 * */
public interface DataFixer {
	/**
	 * Updates a given type reference with an input and the old version and new version.
	 * 
	 * @param type The TypeReference of the input Type.
	 * @param input The data to be updated.
	 * @param version The version that corresponds to the data type.
	 * @param newVersion The version to convert the data type to.
	 * @return The updated value.
	 * */
    <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int version, int newVersion);

    /**
     * Returns the schema for a given key.
     * @param key The key with which to get the Schema
     * @return The Schema
     * */
    Schema getSchema(int key);
}
