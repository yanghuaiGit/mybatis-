/**
 * Copyright ${license.git.copyrightYears} the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * @author Clinton Begin
 */

/**
 * Mybtis对Class的元数据的包装
 * 通过 Reflector 和 PropertyTokenizer 组合使用， 实现了对复杂的属性表达式的解
 * 析，并实现了获取指定属性描述信息的功能。
 */
public class MetaClass {

  //  ReflectorFactory 对象，用于缓存 Reflector 对象
  private final ReflectorFactory reflectorFactory;

  //在创建 MetaClass 时会指定一个类，该 Reflector 对象会用于记录该类相关的元信息
  private final Reflector reflector;

  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    this.reflector = reflectorFactory.findForClass(type);
  }

  //使用静态方法创建 MetaClass 对象
  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

  public MetaClass metaClassForProperty(String name) {
    Class<?> propType = reflector.getGetterType(name);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  //核心方法之一
  public String findProperty(String name) {
    //用 Meta Class. buildProperty（） 方法实现
    StringBuilder prop = buildProperty(name, new StringBuilder());
    return prop.length() > 0 ? prop.toString() : null;
  }

  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      name = name.replace("_", "");
    }
    return findProperty(name);
  }

  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop.getName());
      return metaProp.getSetterType(prop.getChildren());
    } else {
      return reflector.getSetterType(prop.getName());
    }
  }

  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop);
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    return getGetterType(prop);
  }


  private MetaClass metaClassForProperty(PropertyTokenizer prop) {

    // 获取表达式所表示的属性类型
    Class<?> propType = getGetterType(prop);
    //为该属性创建对应的 MetaClass对象
    return MetaClass.forClass(propType, reflectorFactory);
  }


  //针对 Prop rtyTokenizer 中是否包含索引 息做进一步处理
  private Class<?> getGetterType(PropertyTokenizer prop) {

    //获取属性类型
    Class<?> type = reflector.getGetterType(prop.getName());
    //该表达式中是否使用 ［］ 指定了下标 且是 Collection子类
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
      //／通过 TypeParameterResolver 工具类解析属性的类型
      Type returnType = getGenericGetterType(prop.getName());

      //针对 ParameterizedType进行处理，即针对泛型集合类型进行处理
      if (returnType instanceof ParameterizedType) {
        //获取实际的类型参数
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
        //TODO 为什么还要判断长度是否为1
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          //泛型的类型
          returnType = actualTypeArguments[0];
          if (returnType instanceof Class) {
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }

  private Type getGenericGetterType(String propertyName) {
    try {
      //根据 Reflector getMethods 集合中记录的 Invoker 实现类的类型，决定解析 getter 方法返回值类型还是解析字段类型
      Invoker invoker = reflector.getGetInvoker(propertyName);
      if (invoker instanceof MethodInvoker) {
        Field _method = MethodInvoker.class.getDeclaredField("method");
        _method.setAccessible(true);
        Method method = (Method) _method.get(invoker);
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      } else if (invoker instanceof GetFieldInvoker) {
        Field _field = GetFieldInvoker.class.getDeclaredField("field");
        _field.setAccessible(true);
        Field field = (Field) _field.get(invoker);
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException | IllegalAccessException ignored) {
    }
    return null;
  }

  //MetaClass.hasGetter（）和 has Setter（）方法负责判断属性表达式所表示的属性是否有对应的属性
  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasSetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop.getName());
        return metaProp.hasSetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasSetter(prop.getName());
    }
  }

  //MetaClass.hasGetter（）和 has Setter（）方法负责判断属性表达式所表示的属性是否有对应的属性
  //最终都会查找 Reflector.getMethods 集合或 setMethods 集合
  public boolean hasGetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasGetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop);
        return metaProp.hasGetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasGetter(prop.getName());
    }
  }

  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }

  private StringBuilder buildProperty(String name, StringBuilder builder) {
    //PropertyTokenizer 解析复杂的属性表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //是否还有子表达式
    if (prop.hasNext()) {
      //查找 PropertyTokenizer name 对应的属性
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        //追加属性名
        builder.append(propertyName);
        builder.append(".");
        //递归调用
        MetaClass metaProp = metaClassForProperty(propertyName);
        //递归解析 PropertyTokenizer.children 字段，并将解析结果添加到 builder 中保存
        metaProp.buildProperty(prop.getChildren(), builder);
      }
    } else {
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
