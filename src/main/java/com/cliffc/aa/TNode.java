package com.cliffc.aa;

import com.cliffc.aa.type.Type;
import com.cliffc.aa.tvar.TVar;
import org.jetbrains.annotations.NotNull;

// Simple interface to limit TypeNode access to Node.
public interface TNode {
  abstract Type val();                             // My lattice type
  abstract TVar tvar();                            // My Type-Var
  abstract TVar tvar(int x);                       // My xth input TVar
  abstract @NotNull String @NotNull [] argnames(); // Only for FunNodes
  abstract @NotNull TNode[] parms();               // Only for FunNodes
  abstract boolean is_dead();
  abstract int uid();
}
