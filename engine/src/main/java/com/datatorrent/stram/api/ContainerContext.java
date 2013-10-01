/*
 *  Copyright (c) 2012-2013 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.stram.api;

import java.util.HashSet;
import java.util.Set;

import com.datatorrent.api.AttributeMap;
import com.datatorrent.api.Context;

/**
 * <p>ContainerContext interface.</p>
 *
 * @author Chetan Narsude <chetan@datatorrent.com>
 * @since 0.3.5
 */
public interface ContainerContext extends Context
{
  public static final AttributeKey<String> IDENTIFIER = new AttributeKey<String>("IDENTIFIER", String.class);
  public static final AttributeKey<RequestFactory> REQUEST_FACTORY = new AttributeKey<RequestFactory>("REQUEST_FACTORY", RequestFactory.class);

  public class AttributeKey<T> extends AttributeMap.AttributeKey<T>
  {
    public final Class<T> attributeType;

    @SuppressWarnings("LeakingThisInConstructor")
    private AttributeKey(String name, Class<T> type)
    {
      super(ContainerContext.class, name);
      this.attributeType = type;
      INSTANCES.add(this);
    }

    private final static Set<AttributeKey<?>> INSTANCES = new HashSet<AttributeKey<?>>();
  }
}