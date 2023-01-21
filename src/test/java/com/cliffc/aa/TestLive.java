package com.cliffc.aa;

import com.cliffc.aa.node.*;
import com.cliffc.aa.type.*;
import org.junit.Test;

import static com.cliffc.aa.AA.REZ_IDX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestLive {
  @Test public void testBasic() {
    Node fullmem = new ConNode<>(TypeMem.ALLMEM);
    fullmem._val = TypeMem.ALLMEM;

    // Return the number '5' - should be alive with no special memory.
    Node rez = new ConNode<>(TypeInt.con(5));
    rez._val = TypeInt.con(5);

    // Liveness is a backwards flow.  Scope always demands all return results.
    ScopeNode scope = new ScopeNode(false,null,null,fullmem,null,rez,null);

    // Check liveness base case
    scope._live = scope.live();
    assertEquals(TypeMem.ANYMEM,scope._live);

    // Check liveness recursive back one step
    rez._live = rez.live();
    assertEquals(Type.ALL,rez._live);
  }

  @Test public void testNewNode() {
    GVNGCM gvn = Env.GVN;
    Node._INIT0_CNT = 1; // No prims
    // Always memory for the NewNode
    Node mmm = new ConNode<>(TypeMem.ANYMEM).init();

    // Fields
    ConNode fdx = new ConNode(TypeInt.con(5)).init();
    ConNode fdy = new ConNode(TypeInt.con(9)).init();

    // New object, fields x,y holding ints
    StructNode obj = new StructNode(false,false,null,"",Type.ALL);
    obj.add_fld("x",TypeFld.Access.Final,fdx,null);
    obj.add_fld("y",TypeFld.Access.Final,fdy,null);
    obj.close().init();

    NewNode ptr = new NewNode().init();
    Node mem = new StoreNode(mmm,ptr,obj,null).init();

    // Use the object for scope exit
    ScopeNode scope = new ScopeNode(false,null,null,mem,null,ptr,null);
    scope.set_mem(mem);
    scope.set_rez(ptr);
    scope.init();

    // Check 'live' is stable on creation, except for mem & scope
    // which are 'turning around' liveness.
    // Value was computed in a forwards flow.
    for( Node n : new Node[]{mmm,fdx,fdy,obj,ptr,mem,scope} ) {
      if( n != mem && n != scope )
        assertTrue(n.live().isa(n._live));
      assertEquals(n._val,n.value());
    }

    // Check liveness base case
    scope.xliv();
    // Since simple forwards-flow, the default memory is known UNUSED.
    // However, we got provided at least one object.
    TypeMem expected_live = ((TypeMem) mem._val).flatten_live_fields();
    assertEquals(scope._live,expected_live);

    // Check liveness recursive back one step
    mem.xliv();
    assertEquals(mem._live,expected_live); // Object demands of OProj, but OProj passes along request to NewObj
    ptr.xliv();
    assertEquals(TypeMem.ALL,ptr._live);
    mmm.xliv();
    assertEquals(expected_live,mmm._live); // Since ptr is scalar, all memory is alive
    fdx.xliv();
    assertEquals(Type.ALL,fdx._live); // Since ptr is scalar, all memory is alive

  }
}
