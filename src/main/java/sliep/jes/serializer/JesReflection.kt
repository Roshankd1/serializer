@file:Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER", "unused")

package sliep.jes.serializer

import sun.misc.Unsafe
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.lang.reflect.*
import java.net.http.HttpHeaders
import java.util.*
import kotlin.reflect.KClass

/************************************************************\
 ** -------------------  CONSTRUCTORS  ------------------- **
\************************************************************/
/** --------  FUNCTIONS  -------- **/
fun <T> Class<T>.newInstance(vararg params: Any?): T {
    if (isPrimitive) throw UnsupportedOperationException("Can't use reflection on primitive types")
    val constructor = guessFromParameters(name, declaredConstructors, null, params)
    constructor.isAccessible = true
    return constructor.newInstance(*params) as T
}

fun <T> Class<T>.constructor(vararg paramsTypes: KClass<*>): Constructor<out T> = constructor(*Array(paramsTypes.size) { i -> paramsTypes[i].java })
fun <T> Class<T>.constructor(vararg paramsTypes: Class<*>): Constructor<out T> {
    if (isPrimitive) throw UnsupportedOperationException("Can't use reflection on primitive types")
    val constructor = getDeclaredConstructor(*paramsTypes)
    constructor.isAccessible = true
    return constructor
}

@Throws(UnsupportedOperationException::class)
fun <T> Class<T>.newUnsafeInstance(): T {
    if (isPrimitive) throw UnsupportedOperationException("Can't use reflection on primitive types")
    if (!canAllocate) throw UnsupportedOperationException("Cannot allocate abstract class!")
    val clazz = this
    for (allocation in AllocationMethod.values())
        allocation.runCatching { return newInstance(clazz) }
    throw UnsupportedOperationException("Cannot allocate instance of type: $name")
}

fun <T> Class<T>.constructors(modifiers: Int = 0, excludeModifiers: Int = 0): Array<Constructor<out T>> {
    val response = ArrayList<Constructor<out T>>()
    for (constructor in declaredConstructors)
        if ((modifiers == 0 || constructor.modifiers and modifiers == modifiers) && (excludeModifiers == 0 || constructor.modifiers and excludeModifiers == 0) && !response.contains(constructor))
            response.add(constructor as Constructor<out T>)
    return response.toArray(arrayOf())
}

/** --------  VARIABLES  -------- **/
val Class<*>.canAllocate: Boolean
    get() = !Modifier.isAbstract(modifiers)

/************************************************************\
 ** ----------------------  FIELDS  ---------------------- **
\************************************************************/
/** --------  FUNCTIONS  -------- **/
@Throws(NoSuchFieldException::class, IllegalArgumentException::class)
fun <R : Any?> Any.field(name: String, inParent: Boolean = true): R {
    val clazz = when {
        this is Class<*> -> this
        this is KClass<*> -> this.java
        else -> this::class.java
    }
    val fieldR = clazz.fieldR(name, inParent)
    return fieldR[if (Modifier.isStatic(fieldR.modifiers)) null else this] as R
}

@Throws(NoSuchFieldException::class)
fun Class<*>.fieldR(name: String, inParent: Boolean = false): Field {
    if (isPrimitive) throw UnsupportedOperationException("Can't use reflection on primitive types")
    var clazz = this
    var firstError: Throwable? = null
    while (true)
        try {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            return field
        } catch (e: NoSuchFieldException) {
            if (!inParent) throw e
            if (firstError == null) firstError = e
            clazz = clazz.superclass ?: throw firstError
        }
}

fun Class<*>.fields(modifiers: Int = 0, excludeModifiers: Int = 0): Array<Field> {
    val response = ArrayList<Field>()
    var clazz = this
    while (true) {
        for (field in clazz.declaredFields)
            if ((modifiers == 0 || field.modifiers and modifiers == modifiers) && (excludeModifiers == 0 || field.modifiers and excludeModifiers == 0) && !response.contains(field))
                response.add(field)
        clazz = clazz.superclass ?: return response.toArray(arrayOf())
    }
}


@Throws(NoSuchMethodException::class)
fun <R : Any?> Any.callGetter(fieldName: String) = try {
    invokeMethod("get${fieldName[0].toUpperCase()}${fieldName.substring(1)}") as R
} catch (e: NoSuchMethodException) {
    try {
        invokeMethod("is${fieldName[0].toUpperCase()}${fieldName.substring(1)}") as R
    } catch (ignore: NoSuchMethodException) {
        throw e
    }
}

@Throws(NoSuchMethodException::class)
fun Any.callSetter(fieldName: String, value: Any?) {
    invokeMethod<Any?>("set${fieldName[0].toUpperCase()}${fieldName.substring(1)}", value)
}

/** --------  VARIABLES  -------- **/
val MODIFIERS get() = lateInit { kotlin.runCatching { kotlin.runCatching { Field::class.java.fieldR("accessFlags") }.getOrDefault(Field::class.java.fieldR("modifiers")) }.getOrNull() }
inline val Class<*>.CONSTANTS: Array<Any?>
    get() {
        val fields = HttpHeaders::class.fields(Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL)
        return Array(fields.size) { i -> fields[i][null] }
    }
inline var Field.isFinal: Boolean
    get() = Modifier.isFinal(modifiers)
    set(value) {
        val accessFlags = MODIFIERS
        if (accessFlags != null && Modifier.isFinal(modifiers) != value) kotlin.runCatching {
            accessFlags[this] = modifiers and if (value) Modifier.FINAL else Modifier.FINAL.inv()
        }
    }

/************************************************************\
 ** ---------------------  METHODS  ---------------------- **
\************************************************************/
/** --------  FUNCTIONS  -------- **/

@Throws(NoSuchMethodException::class, IllegalArgumentException::class, InvocationTargetException::class)
fun <R : Any?> Any.invokeMethod(name: String, vararg params: Any?): R {
    var clazz = when {
        this is Class<*> -> this
        this is KClass<*> -> this.java
        else -> this::class.java
    }
    if (clazz.isPrimitive) throw UnsupportedOperationException("Can't use reflection on primitive types")
    var firstError: Throwable? = null
    while (true)
        try {
            val method = guessFromParameters(clazz.name, clazz.declaredMethods, name, params)
            method.isAccessible = true
            return method.invoke(if (Modifier.isStatic(method.modifiers)) null else this, *params) as R
        } catch (e: NoSuchMethodException) {
            if (firstError == null) firstError = e
            clazz = clazz.superclass ?: throw firstError
        }
}

@Throws(NoSuchMethodException::class)
fun Class<*>.method(name: String, vararg paramsTypes: KClass<*>) = methodX(name, true, *paramsTypes)

@Throws(NoSuchMethodException::class)
fun Class<*>.method(name: String, vararg paramsTypes: Class<*>) = methodX(name, true, *paramsTypes)

@Throws(NoSuchMethodException::class)
fun Class<*>.methodX(name: String, searchParent: Boolean, vararg paramsTypes: KClass<*>): Method = methodX(name, searchParent, *Array(paramsTypes.size) { i -> paramsTypes[i].java })

@Throws(NoSuchMethodException::class)
fun Class<*>.methodX(name: String, searchParent: Boolean, vararg paramsTypes: Class<*>): Method {
    var clazz = this
    while (true)
        try {
            val method = clazz.getDeclaredMethod(name, *paramsTypes)
            method.isAccessible = true
            return method
        } catch (e: Throwable) {
            if (!searchParent) throw e
            clazz = clazz.superclass ?: throw e
        }
}

fun Class<*>.methods(modifiers: Int = 0, excludeModifiers: Int = 0): Array<Method> {
    val response = ArrayList<Method>()
    var clazz = this
    while (true) {
        for (method in clazz.declaredMethods)
            if ((modifiers == 0 || method.modifiers and modifiers == modifiers) && (excludeModifiers == 0 || method.modifiers and excludeModifiers == 0) && !response.contains(method))
                response.add(method)
        clazz = clazz.superclass ?: return response.toArray(arrayOf())
    }
}

@Throws(NoSuchMethodException::class)
fun <M : Executable> guessFromParameters(clazzName: String, members: Array<out M>, name: String?, params: Array<out Any?>) = guessFromParametersTypes(clazzName, members, name, kotlin.Array(params.size) { i -> if (params[i] == null) null else params[i]!!::class })

@Throws(NoSuchMethodException::class)
fun <M : Executable> guessFromParametersTypes(clazzName: String, members: Array<out M>, name: String?, params: Array<out KClass<*>?>): M {
    bob@ for (method in members) {
        if (name != null && method.name != name) continue
        val types = method.parameterTypes
        if (types.size != params.size) continue
        for (i in types.indices)
            if (params[i] != null && !types[i].kotlin.javaObjectType.isAssignableFrom(params[i]!!.javaObjectType)) continue@bob
        method.isAccessible = true
        return method
    }
    throw NoSuchMethodException(methodToString(clazzName, name
            ?: "<init>", Array(params.size) { i -> params[i]?.java }))
}

fun methodToString(className: String, name: String, argTypes: Array<out Class<*>?>): String {
    val joiner = StringJoiner(", ", "$className.$name(", ")")
    for (i in argTypes.indices) joiner.add(argTypes[i]?.name)
    return joiner.toString()
}

/** --------  VARIABLES  -------- **/
inline val Executable.signature
    get() = methodToString(declaringClass.name, name, parameterTypes)

/************************************************************\
 ** ---------------------  CLASSES  ---------------------- **
\************************************************************/
/** --------  VARIABLES  -------- **/
inline val Class<*>.unwrappedClass: Class<*> get() = kotlin.unwrappedClass
inline val Class<*>.wrappedClass: Class<*> get() = kotlin.wrappedClass
inline val Class<*>.isTypePrimitive: Boolean get() = kotlin.isTypePrimitive
inline val Class<*>.dimensions
    get() = name.lastIndexOf('[') + 1
/************************************************************\
 ** ---------------------  UTILITY  ---------------------- **
\************************************************************/
enum class AllocationMethod {
    NATIVE_NEW_INSTANCE {
        override fun <T> newInstance(c: Class<T>): T {
            val constructor = c.getDeclaredConstructor()
            constructor.isAccessible = true
            return constructor.newInstance()
        }
    },
    UNSAFE {
        override fun <T> newInstance(c: Class<T>): T {
            val theUnsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            theUnsafe.isAccessible = true
            val allocateInstance = Unsafe::class.java.getDeclaredMethod("allocateInstance", Class::class.java)
            return allocateInstance.invoke(theUnsafe.get(null), c) as T
        }
    },
    OBJ_INPUT_STREAM {
        override fun <T> newInstance(c: Class<T>): T {
            val newInstance = ObjectInputStream::class.java.getDeclaredMethod("newInstance", Class::class.java, Class::class.java)
            newInstance.isAccessible = true
            return newInstance.invoke(null, c, Object::class.java) as T
        }
    },
    CONSTRUCTOR_ID {
        override fun <T> newInstance(c: Class<T>): T {
            val getConstructorId = ObjectStreamClass::class.java.getDeclaredMethod("getConstructorId", Class::class.java)
            getConstructorId.isAccessible = true
            val constructorId = getConstructorId.invoke(null, Object::class.java) as Int
            val newInstance = ObjectStreamClass::class.java.getDeclaredMethod("newInstance", Class::class.java, Int::class.javaPrimitiveType)
            newInstance.isAccessible = true
            return newInstance.invoke(null, c, constructorId) as T
        }
    };

    @Throws(Exception::class)
    abstract fun <T> newInstance(c: Class<T>): T
}