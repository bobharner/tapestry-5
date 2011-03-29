// Copyright 2011 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.plastic;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.apache.tapestry5.internal.plastic.PlasticInternalUtils;

/**
 * Describes a {@link PlasticMethod} in terms of a method name, a set of modifiers
 * (public, private, static, final, etc.), a return type, types of method arguments,
 * and types of checked exceptions. Types are represented as Java source names:
 * either names of primitives ("void", "byte", "long") or fully qualified class names ("java.lang.Object",
 * "java.lang.Runnable"). ASM refers to this as "class name".
 * <p>
 * MethodDescriptions are immutable, and properly implement equals() and hashCode(); they are often used as keys in
 * Maps.
 * <p>
 * The natural sort order for a MethodDescription is ascending order by method name, then descending order by number of
 * parameters (for the same name). Sort order is not currently specified for overrides of the same method with the same
 * number of parameters.
 * <p>
 * TODO: Handling generic types.
 */
public class MethodDescription implements Comparable<MethodDescription>
{
    /**
     * The full set of modifier flags for the method.
     */
    public final int modifiers;

    /** The Java source name for the return type, e.g., "void", "short", "java.util.Map", "java.lang.String[]". */
    public final String returnType;

    /** The name of the method. */
    public final String methodName;

    /**
     * A non-null array of Java source names for arguments. Do not modify
     * the contents of this array.
     */
    public final String[] argumentTypes;

    /** A non-null array of Java source names for checked exceptions. Do not modify the contents of this array. */
    public final String[] checkedExceptionTypes;

    /**
     * Convenience constructor for public methods that have no checked exceptions.
     * 
     * @param returnType
     *            return type as type name
     * @param methodName
     *            name of method
     * @param argumentTypes
     *            type names for arguments
     */
    public MethodDescription(String returnType, String methodName, String... argumentTypes)
    {
        this(Modifier.PUBLIC, returnType, methodName, argumentTypes, null);
    }

    /**
     * @param modifiers
     * @param returnType
     *            Java source name for the return type
     * @param methodName
     * @param argumentTypes
     *            may be null
     * @param checkedExceptionTypes
     *            may be null
     */
    public MethodDescription(int modifiers, String returnType, String methodName, String[] argumentTypes,
            String[] checkedExceptionTypes)
    {
        assert PlasticInternalUtils.isNonBlank(returnType);
        assert PlasticInternalUtils.isNonBlank(methodName);

        this.modifiers = modifiers;
        this.returnType = returnType;
        this.methodName = methodName;

        this.argumentTypes = PlasticInternalUtils.orEmpty(argumentTypes);
        this.checkedExceptionTypes = PlasticInternalUtils.orEmpty(checkedExceptionTypes);
    }

    public MethodDescription withModifiers(int newModifiers)
    {
        return new MethodDescription(newModifiers, returnType, methodName, argumentTypes, checkedExceptionTypes);
    }

    /** Creates a MethodDescription from a Java Method. */
    public MethodDescription(Method method)
    {
        this(method.getModifiers(), toTypeName(method.getReturnType()), method.getName(), toTypeNames(method
                .getParameterTypes()), toTypeNames(method.getExceptionTypes()));
    }

    @SuppressWarnings("rawtypes")
    private static String toTypeName(Class type)
    {
        return type.getName();
    }

    @SuppressWarnings("rawtypes")
    private static String[] toTypeNames(Class[] types)
    {
        String[] result = new String[types.length];

        for (int i = 0; i < types.length; i++)
            result[i] = toTypeName(types[i]);

        return result;
    }

    public int compareTo(MethodDescription o)
    {
        int result = methodName.compareTo(o.methodName);

        if (result == 0)
            result = o.argumentTypes.length - argumentTypes.length;

        return result;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;

        result = prime * result + Arrays.hashCode(argumentTypes);
        result = prime * result + Arrays.hashCode(checkedExceptionTypes);
        result = prime * result + methodName.hashCode();
        result = prime * result + modifiers;
        result = prime * result + returnType.hashCode();

        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;

        MethodDescription other = (MethodDescription) obj;

        if (!methodName.equals(other.methodName))
            return false;

        // TODO: I think this tripped me up in Tapestry at some point, as
        // there were modifiers that cause some problem, such as abstract
        // or deprecated or something. May need a mask of modifiers we
        // care about for equals()/hashCode() purposes.

        if (modifiers != other.modifiers)
            return false;

        if (!returnType.equals(other.returnType))
            return false;

        if (!Arrays.equals(argumentTypes, other.argumentTypes))
            return false;

        if (!Arrays.equals(checkedExceptionTypes, other.checkedExceptionTypes))
            return false;

        return true;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();

        // TODO: Not 100% sure that methodNode.access is exactly the same
        // as modifiers. We'll have to see.

        if (modifiers != 0)
            builder.append(Modifier.toString(modifiers)).append(" ");

        builder.append(returnType).append(" ").append(methodName).append("(");

        String sep = "";

        for (String name : argumentTypes)
        {
            builder.append(sep);
            builder.append(name);

            sep = ", ";
        }

        builder.append(")");

        sep = " throws ";

        for (String name : checkedExceptionTypes)
        {
            builder.append(sep);
            builder.append(name);

            sep = ", ";
        }

        return builder.toString();
    }

    // TODO: convienience methods: isStatic(), isPrivate()
}
