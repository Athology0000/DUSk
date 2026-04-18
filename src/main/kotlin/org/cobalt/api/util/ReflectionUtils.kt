package org.cobalt.api.util

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

object ReflectionUtils {

  @Suppress("UNCHECKED_CAST")
  @JvmStatic
  fun <T> getField(instance: Any, fieldName: String): T {
    val field = getField(instance::class.java, fieldName)
    field.makeAccessible()
    return field[instance] as T
  }

  @JvmStatic
  fun setField(instance: Any, fieldName: String, value: Any?) {
    val field = getField(instance::class.java, fieldName)
    field.makeAccessible()
    field[instance] = value
  }

  @JvmStatic
  private fun getField(clazz: Class<*>, fieldName: String): Field {
    var current: Class<*>? = clazz

    while (current != null) {
      try {
        return current.getDeclaredField(fieldName)
      } catch (_: NoSuchFieldException) {
        // Ignore and check superclass
      }

      current = current.superclass
    }

    throw RuntimeException("Field $fieldName not found in class $clazz")
  }

  @Suppress("UNCHECKED_CAST")
  @JvmStatic
  fun <T> invokeMethod(instance: Any, methodName: String, paramTypes: Array<Class<*>>, vararg args: Any?): T {
    val method = getMethod(instance::class.java, methodName, paramTypes)
    method.makeAccessible()
    return method.invoke(instance, *args) as T
  }

  @JvmStatic
  private fun getMethod(clazz: Class<*>, methodName: String, paramTypes: Array<Class<*>>): Method {
    var current: Class<*>? = clazz

    while (current != null) {
      try {
        return current.getDeclaredMethod(methodName, *paramTypes)
      } catch (_: NoSuchMethodException) {
        // Ignore and check superclass
      }

      current = current.superclass
    }

    throw RuntimeException("Method $methodName not found in class $clazz")
  }

  @Suppress("UNCHECKED_CAST")
  @JvmStatic
  fun <T> getStaticField(clazz: Class<*>, fieldName: String): T {
    val field = getField(clazz, fieldName)
    field.makeAccessible()
    return field[null] as T
  }

  @JvmStatic
  fun setStaticField(clazz: Class<*>, fieldName: String, value: Any?) {
    val field = getField(clazz, fieldName)
    field.makeAccessible()
    field[null] = value
  }

  @JvmStatic
  fun <T> createInstance(clazz: Class<T>, paramTypes: Array<Class<*>>, vararg args: Any?): T {
    val constructor: Constructor<T> = clazz.getDeclaredConstructor(*paramTypes)
    constructor.makeAccessible()
    return constructor.newInstance(*args)
  }

  @JvmStatic
  fun getAllFields(clazz: Class<*>): List<Field> {
    val fields = mutableListOf<Field>()
    var current: Class<*>? = clazz

    while (current != null) {
      fields += current.declaredFields
      current = current.superclass
    }

    return fields
  }

  @JvmStatic
  fun getAllMethods(clazz: Class<*>): List<Method> {
    val methods = mutableListOf<Method>()
    var current: Class<*>? = clazz

    while (current != null) {
      methods += current.declaredMethods
      current = current.superclass
    }

    return methods
  }

  @JvmStatic
  fun <A : Annotation> getFieldsWithAnnotation(clazz: Class<*>, annotation: Class<A>): List<Field> {
    return getAllFields(clazz).filter { it.isAnnotationPresent(annotation) }
  }

  @JvmStatic
  fun <A : Annotation> getMethodsWithAnnotation(clazz: Class<*>, annotation: Class<A>): List<Method> {
    return getAllMethods(clazz).filter { it.isAnnotationPresent(annotation) }
  }

  @JvmStatic
  fun setFields(instance: Any, fieldValues: Map<String, Any?>) {
    for ((name, value) in fieldValues) {
      setField(instance, name, value)
    }
  }

  @JvmStatic
  fun getFields(instance: Any, fieldNames: List<String>): Map<String, Any?> {
    return fieldNames.associateWith { getField(instance, it) }
  }

  @JvmStatic
  inline fun <reified T> getFieldTypeSafe(instance: Any, fieldName: String): T =
    getField(instance, fieldName)

  @JvmStatic
  inline fun <reified T> getStaticFieldTypeSafe(clazz: Class<*>, fieldName: String): T =
    getStaticField(clazz, fieldName)

  @JvmStatic
  inline fun <reified T> invokeMethodTypeSafe(
    instance: Any,
    methodName: String,
    paramTypes: Array<Class<*>>,
    vararg args: Any?,
  ): T =
    invokeMethod(instance, methodName, paramTypes, *args)

  @JvmStatic
  fun <T : AccessibleObject> T.makeAccessible(): T {
    this.isAccessible = true
    return this
  }

}
