/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jf.dexlib;

import java.util.TreeMap;

/**
 * Enumeration of all the top-level item types.
 */
public enum ItemType {
    TYPE_HEADER_ITEM(               0x0000, "header_item"),
    TYPE_STRING_ID_ITEM(            0x0001, "string_id_item"),
    TYPE_TYPE_ID_ITEM(              0x0002, "type_id_item"),
    TYPE_PROTO_ID_ITEM(             0x0003, "proto_id_item"),
    TYPE_FIELD_ID_ITEM(             0x0004, "field_id_item"),
    TYPE_METHOD_ID_ITEM(            0x0005, "method_id_item"),
    TYPE_CLASS_DEF_ITEM(            0x0006, "class_def_item"),
    TYPE_MAP_LIST(                  0x1000, "map_list"),
    TYPE_TYPE_LIST(                 0x1001, "type_list"),
    TYPE_ANNOTATION_SET_REF_LIST(   0x1002, "annotation_set_ref_list"),
    TYPE_ANNOTATION_SET_ITEM(       0x1003, "annotation_set_item"),
    TYPE_CLASS_DATA_ITEM(           0x2000, "class_data_item"),
    TYPE_CODE_ITEM(                 0x2001, "code_item"),
    TYPE_STRING_DATA_ITEM(          0x2002, "string_data_item"),
    TYPE_DEBUG_INFO_ITEM(           0x2003, "debug_info_item"),
    TYPE_ANNOTATION_ITEM(           0x2004, "annotation_item"),
    TYPE_ENCODED_ARRAY_ITEM(        0x2005, "encoded_array_item"),
    TYPE_ANNOTATIONS_DIRECTORY_ITEM(0x2006, "annotations_directory_item"),
    TYPE_MAP_ITEM(                  -1,     "map_item"),
    TYPE_TYPE_ITEM(                 -1,     "type_item"),
    TYPE_EXCEPTION_HANDLER_ITEM(    -1,     "exception_handler_item"),
    TYPE_ANNOTATION_SET_REF_ITEM(   -1,     "annotation_set_ref_item");

    /** A map to facilitate looking up an <code>ItemType</code> by ordinal */
    private final static TreeMap<Integer, ItemType> itemTypeIntegerMap;

    /** builds the <code>itemTypeIntegerMap</code> object */ 
    static {
	    itemTypeIntegerMap = new TreeMap<Integer, ItemType>();

        for (ItemType itemType: ItemType.values()) {
            itemTypeIntegerMap.put(itemType.getMapValue(), itemType);
        }
    }
    
    /** value when represented in a MapItem */
    private final int mapValue;

    /** non-null; name of the type */
    private final String typeName;

    /**
     * Constructs an instance.
     * 
     * @param mapValue value when represented in a MapItem
     * @param typeName non-null; name of the type
     */
    private ItemType(int mapValue, String typeName) {
        this.mapValue = mapValue;
        this.typeName = typeName;
    }

    /**
     * Gets the map value.
     * 
     * @return the map value
     */
    public int getMapValue() {
        return mapValue;
    }
    
    /**
     * Gets the type name.
     * 
     * @return non-null; the type name
     */
    public String getTypeName() {
        return typeName;
    }

    /**
     * Converts an int value to the corresponding ItemType enum value,
     * or null if the value isn't a valid ItemType value
     * 
     * @param itemType the int value to convert to an ItemType
     * @return the ItemType enum value corresponding to itemType, or null
     * if not a valid ItemType value 
     */
    public static ItemType fromInt(int itemType) {
        return itemTypeIntegerMap.get(itemType);
    }
}