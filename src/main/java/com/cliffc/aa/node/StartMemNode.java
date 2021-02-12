package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.tvar.*;
import com.cliffc.aa.type.*;

// Program memory start
public class StartMemNode extends Node {
  public StartMemNode(StartNode st) { super(OP_STMEM,st); }
  @Override public boolean is_mem() { return true; }
  @Override public Type value(GVNGCM.Mode opt_mode) {
    // All things are '~use' (to-be-allocated)
    return TypeMem.ANYMEM;
  }
  @Override public boolean unify( boolean test ) {
    // Self should always should be a TMem
    TVar tvar = tvar();
    if( tvar instanceof TMem ) return false;
    return test || tvar.unify(new TMem(this),false);
  }
  @Override public TypeMem all_live() { return TypeMem.ALLMEM; }
  // StartMemNodes are never equal
  @Override public int hashCode() { return 123456789+2; }
  @Override public boolean equals(Object o) { return this==o; }
}
