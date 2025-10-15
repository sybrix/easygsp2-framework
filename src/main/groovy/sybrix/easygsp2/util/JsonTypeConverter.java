package sybrix.easygsp2.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

public class JsonTypeConverter {

        private static final ObjectMapper mapper = new ObjectMapper();

        /**
         * Converts a Java object to a JSON string showing property types instead of values.
         *
         * @param obj The object to convert
         * @return JSON string representing the structure with types
         * @throws IllegalAccessException if a field cannot be accessed
         */
        public static String toJsonWithTypes(Object obj) throws IllegalAccessException {
                ObjectNode rootNode = mapper.createObjectNode();
                buildTypeJson(rootNode, obj);
                try {
                        // Java 8 compatible pretty print
                        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
                } catch (Exception e) {
                        throw new RuntimeException("Failed to serialize type JSON", e);
                }
        }

        private static void buildTypeJson(ObjectNode node, Object obj) throws IllegalAccessException {
                if (obj == null) {
                        return;
                }

                Class<?> clazz = obj.getClass();
                for (Field field : clazz.getDeclaredFields()) {
                        field.setAccessible(true);
                        Object value = field.get(obj);

                        // Use the new overloaded method with generic type information
                        String jsonType = getJsonType(field.getType(), field.getGenericType());

                        // Check if it's a custom object (not just "object")
                        if (jsonType.equals("object") || (!jsonType.startsWith("array") && !jsonType.startsWith("object<") &&
                                !isPrimitiveJsonType(jsonType) && value != null)) {
                                ObjectNode childNode = mapper.createObjectNode();
                                buildTypeJson(childNode, value);
                                node.set(field.getName(), childNode);

                        } else if (jsonType.startsWith("array") && value instanceof Collection) {
                                ArrayNode arr = mapper.createArrayNode();
                                Collection<?> collection = (Collection<?>) value;
                                if (!collection.isEmpty()) {
                                        Object first = collection.iterator().next();
                                        if (first != null && !isPrimitiveOrBasicType(first.getClass())) {
                                                ObjectNode child = mapper.createObjectNode();
                                                buildTypeJson(child, first);
                                                arr.add(child);
                                        } else {
                                                arr.add(getJsonType(first != null ? first.getClass() : Object.class, null));
                                        }
                                } else {
                                        // Empty array, just show the type
                                        arr.add(jsonType);
                                }
                                node.set(field.getName(), arr);

                        } else {
                                node.put(field.getName(), jsonType);
                        }
                }
        }

        private static boolean isPrimitiveJsonType(String type) {
                return "string".equals(type) || "number".equals(type) ||
                       "boolean".equals(type) || "null".equals(type);
        }

        private static boolean isPrimitiveOrBasicType(Class<?> clazz) {
                return clazz.isPrimitive() || clazz == String.class ||
                       Number.class.isAssignableFrom(clazz) ||
                       clazz == Boolean.class || clazz == Character.class ||
                       clazz.getName().startsWith("java.lang.");
        }

        public static String getJsonType(Class<?> type, Object value) {
                if (Number.class.isAssignableFrom(type) ||
                        type.isPrimitive() && (type == int.class || type == long.class ||
                                type == double.class || type == float.class ||
                                type == short.class || type == byte.class)) {
                        return "number";
                } else if (type == boolean.class || type == Boolean.class) {
                        return "boolean";
                } else if (type == String.class || type.isEnum() || type == char.class || type == Character.class) {
                        return "string";
                } else if (Collection.class.isAssignableFrom(type) || type.isArray()) {
                        return "array";
                } else if (Map.class.isAssignableFrom(type) ||
                        (!type.isPrimitive() && !type.getName().startsWith("java."))) {
                        return "object";
                }
                return "string"; // fallback for unsupported types
        }

        /**
         * Converts a Java class to a JSON string showing property types instead of values.
         * This method doesn't require an instance of the class.
         *
         * @param clazz The class to convert
         * @return JSON string representing the structure with types
         */
        public static String classToJsonTypes(Class<?> clazz) {
                ObjectNode rootNode = mapper.createObjectNode();
                buildClassTypeJson(rootNode, clazz, new HashSet<Class<?>>());
                try {
                        // Java 8 compatible pretty print
                        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
                } catch (Exception e) {
                        throw new RuntimeException("Failed to serialize class type JSON", e);
                }
        }
        
        /**
         * Converts a Java class to a JSON Object (Map) showing property types instead of values.
         * This method doesn't require an instance of the class and returns an Object suitable
         * for template rendering without escaping.
         *
         * @param clazz The class to convert
         * @return Object (Map) representing the structure with types
         */
        public static Object classToJsonTypesAsObject(Class<?> clazz) {
                ObjectNode rootNode = mapper.createObjectNode();
                buildClassTypeJson(rootNode, clazz, new HashSet<Class<?>>());
                try {
                        // Convert to Map for proper template rendering
                        return mapper.convertValue(rootNode, Map.class);
                } catch (Exception e) {
                        throw new RuntimeException("Failed to convert class type to object", e);
                }
        }

        private static void buildClassTypeJson(ObjectNode node, Class<?> clazz, Set<Class<?>> processedClasses) {
                // Prevent infinite recursion for circular references
                if (processedClasses.contains(clazz)) {
                        return;
                }
                processedClasses.add(clazz);

                // Process all declared fields in the class and its superclasses
                Class<?> currentClass = clazz;
                while (currentClass != null && currentClass != Object.class) {
                        for (Field field : currentClass.getDeclaredFields()) {
                                // Skip static and synthetic fields
                                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                                    field.isSynthetic()) {
                                        continue;
                                }

                                String fieldName = field.getName();
                                Class<?> fieldType = field.getType();
                                Type genericType = field.getGenericType();

                                // Get the JSON type representation
                                String jsonType = getJsonType(fieldType, genericType);

                                // Handle complex types
                                if (fieldType.isArray()) {
                                        // For arrays, create an array with the component type structure
                                        ArrayNode arrayNode = mapper.createArrayNode();
                                        Class<?> componentType = fieldType.getComponentType();
                                        
                                        if (!isPrimitiveOrBasicType(componentType) &&
                                            !componentType.getName().startsWith("java.") &&
                                            !componentType.isEnum()) {
                                                // For custom objects, create the structure
                                                ObjectNode elementNode = mapper.createObjectNode();
                                                Set<Class<?>> branchProcessedClasses = new HashSet<>(processedClasses);
                                                buildClassTypeJson(elementNode, componentType, branchProcessedClasses);
                                                arrayNode.add(elementNode);
                                        } else {
                                                // For primitive types, just add the type string
                                                String componentJsonType = getJsonType(componentType, null);
                                                arrayNode.add(componentJsonType);
                                        }
                                        
                                        node.set(fieldName, arrayNode);

                                } else if (Collection.class.isAssignableFrom(fieldType)) {
                                        // For collections, create an array with the element type structure
                                        ArrayNode arrayNode = mapper.createArrayNode();
                                        
                                        if (genericType instanceof ParameterizedType) {
                                                ParameterizedType pType = (ParameterizedType) genericType;
                                                Type[] typeArgs = pType.getActualTypeArguments();
                                                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                                                        Class<?> elementType = (Class<?>) typeArgs[0];
                                                        
                                                        if (!isPrimitiveOrBasicType(elementType) &&
                                                            !elementType.getName().startsWith("java.") &&
                                                            !elementType.isEnum()) {
                                                                // For custom objects, create the structure
                                                                ObjectNode elementNode = mapper.createObjectNode();
                                                                Set<Class<?>> branchProcessedClasses = new HashSet<>(processedClasses);
                                                                buildClassTypeJson(elementNode, elementType, branchProcessedClasses);
                                                                arrayNode.add(elementNode);
                                                        } else {
                                                                // For primitive types, just add the type string
                                                                arrayNode.add(getJsonType(elementType, null));
                                                        }
                                                }
                                        } else {
                                                // No generic info, just show generic array
                                                arrayNode.add("object");
                                        }
                                        
                                        node.set(fieldName, arrayNode);

                                } else if (Map.class.isAssignableFrom(fieldType)) {
                                        // Already handled by getJsonType with generic info
                                        node.put(fieldName, jsonType);


                                } else if (!isPrimitiveOrBasicType(fieldType) &&
                                          !fieldType.getName().startsWith("java.") &&
                                          !fieldType.isEnum()) {
                                        // For custom objects, create a nested structure
                                        ObjectNode childNode = mapper.createObjectNode();

                                        // Create a new set for this branch to allow the same class
                                        // to appear in different branches of the tree
                                        Set<Class<?>> branchProcessedClasses = new HashSet<>(processedClasses);
                                        buildClassTypeJson(childNode, fieldType, branchProcessedClasses);

                                        // Only add the child node if it has fields
                                        if (childNode.size() > 0) {
                                                node.set(fieldName, childNode);
                                        } else {
                                                // If no fields, just show the type name
                                                node.put(fieldName, fieldType.getSimpleName());
                                        }
                                } else {
                                        // For primitive and basic types, just add the type
                                        node.put(fieldName, jsonType);
                                }
                        }
                        // Move to superclass
                        currentClass = currentClass.getSuperclass();
                }
        }

        /**
         * Overloaded method to handle Type information for better JSON type detection
         * @param clazz The class type
         * @param genericType The generic type information (can be null)
         * @return JSON type string representation
         */
        public static String getJsonType(Class<?> clazz, Type genericType) {
                // Handle basic types first
                if (Number.class.isAssignableFrom(clazz) ||
                        clazz.isPrimitive() && (clazz == int.class || clazz == long.class ||
                                clazz == double.class || clazz == float.class ||
                                clazz == short.class || clazz == byte.class)) {
                        return "number";
                } else if (clazz == boolean.class || clazz == Boolean.class) {
                        return "boolean";
                } else if (clazz == String.class || clazz.isEnum() || clazz == char.class || clazz == Character.class) {
                        return "string";
                } else if (clazz == void.class || clazz == Void.class) {
                        return "null";
                } else if (Collection.class.isAssignableFrom(clazz) || clazz.isArray()) {
                        // If we have generic type info, we can be more specific
                        if (genericType instanceof ParameterizedType) {
                                ParameterizedType pType = (ParameterizedType) genericType;
                                Type[] typeArgs = pType.getActualTypeArguments();
                                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                                        Class<?> elementType = (Class<?>) typeArgs[0];
                                        String elementJsonType = getJsonType(elementType, null);
                                        return "array<" + elementJsonType + ">";
                                }
                        }
                        return "array";
                } else if (Map.class.isAssignableFrom(clazz)) {
                        // For maps, return a more descriptive type
                        if (genericType instanceof ParameterizedType) {
                                ParameterizedType pType = (ParameterizedType) genericType;
                                Type[] typeArgs = pType.getActualTypeArguments();
                                if (typeArgs.length == 2) {
                                        String keyType = typeArgs[0] instanceof Class ? getJsonType((Class<?>) typeArgs[0], null) : "string";
                                        String valueType = typeArgs[1] instanceof Class ? getJsonType((Class<?>) typeArgs[1], null) : "object";
                                        return "object<" + keyType + "," + valueType + ">";
                                }
                        }
                        return "object";
                } else if (!clazz.isPrimitive() && !clazz.getName().startsWith("java.")) {
                        // For custom classes, try to provide the class name as the type
                        String className = clazz.getSimpleName();
                        return className.isEmpty() ? "object" : className;
                }
                return "object"; // fallback for unsupported types
        }

        /**
         * Detects if a class has circular references in its object graph.
         * This is useful for documentation purposes to warn about potential circular references.
         *
         * @param clazz The class to check for circular references
         * @return true if circular references are detected, false otherwise
         */
        public static boolean hasCircularReferences(Class<?> clazz) {
                Set<Class<?>> visitedClasses = new HashSet<>();
                return hasCircularReferencesHelper(clazz, visitedClasses, new HashSet<>());
        }
        
        private static boolean hasCircularReferencesHelper(Class<?> clazz, Set<Class<?>> visitedClasses, Set<Class<?>> currentPath) {
                // Skip primitive types and common Java types
                if (clazz == null || isPrimitiveOrBasicType(clazz) || 
                    clazz.getName().startsWith("java.") || 
                    clazz.isEnum() || clazz.isInterface()) {
                        return false;
                }
                
                // If we've seen this class in the current path, we have a circular reference
                if (currentPath.contains(clazz)) {
                        return true;
                }
                
                // If we've already fully processed this class, skip it
                if (visitedClasses.contains(clazz)) {
                        return false;
                }
                
                // Add to current path
                currentPath.add(clazz);
                
                try {
                        // Check all fields in the class and its superclasses
                        Class<?> currentClass = clazz;
                        while (currentClass != null && currentClass != Object.class) {
                                for (Field field : currentClass.getDeclaredFields()) {
                                        // Skip static and synthetic fields
                                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) ||
                                            field.isSynthetic()) {
                                                continue;
                                        }
                                        
                                        Class<?> fieldType = field.getType();
                                        Type genericType = field.getGenericType();
                                        
                                        // Check the field type itself
                                        if (!isPrimitiveOrBasicType(fieldType) && 
                                            !fieldType.getName().startsWith("java.")) {
                                                if (hasCircularReferencesHelper(fieldType, visitedClasses, new HashSet<>(currentPath))) {
                                                        return true;
                                                }
                                        }
                                        
                                        // Check generic types in collections
                                        if (genericType instanceof ParameterizedType) {
                                                ParameterizedType pType = (ParameterizedType) genericType;
                                                for (Type typeArg : pType.getActualTypeArguments()) {
                                                        if (typeArg instanceof Class) {
                                                                Class<?> genericClass = (Class<?>) typeArg;
                                                                if (!isPrimitiveOrBasicType(genericClass) && 
                                                                    !genericClass.getName().startsWith("java.")) {
                                                                        if (hasCircularReferencesHelper(genericClass, visitedClasses, new HashSet<>(currentPath))) {
                                                                                return true;
                                                                        }
                                                                }
                                                        }
                                                }
                                        }
                                }
                                // Move to superclass
                                currentClass = currentClass.getSuperclass();
                        }
                } finally {
                        // Remove from current path
                        currentPath.remove(clazz);
                        // Mark as visited
                        visitedClasses.add(clazz);
                }
                
                return false;
        }

        // Example usage
        public static void main(String[] args) throws Exception {
                class Address {
                        String street;
                        int zip;
                        List<String> phoneNumbers;
                }
                class Person {
                        String name;
                        int age;
                        boolean active;
                        Address address;
                        Map<String, Object> metadata;
                        List<Address> previousAddresses;
                }

                // Example 1: Using toJsonWithTypes with an instance
                Person p = new Person();
                p.name = "Alice";
                p.age = 30;
                p.active = true;
                p.address = new Address();
                p.address.street = "123 Main St";
                p.address.zip = 12345;

                System.out.println("=== toJsonWithTypes (requires instance) ===");
                System.out.println(toJsonWithTypes(p));

                System.out.println("\n=== classToJsonTypes (no instance needed) ===");
                System.out.println(classToJsonTypes(Person.class));
        }
}
