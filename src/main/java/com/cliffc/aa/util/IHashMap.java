package com.cliffc.aa.util;

import java.util.HashMap;

@SuppressWarnings("unchecked")
public class IHashMap {
  private final HashMap _map = new HashMap();
  public <T> T put(T kv) { _map.put(kv,kv); return kv; }
  public <T> T put(T k, T v) { _map.put(k,v); return v; }
  public <T> T get(T key) { return (T)_map.get(key); }
  public void clear() { _map.clear(); }
}
