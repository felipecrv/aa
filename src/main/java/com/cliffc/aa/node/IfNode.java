package com.cliffc.aa.node;

import com.cliffc.aa.AA;
import com.cliffc.aa.Env;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeNil;
import com.cliffc.aa.type.TypeTuple;

import static com.cliffc.aa.AA.unimpl;

// Split control
public class IfNode extends Node {
  public IfNode( Node ctrl, Node pred ) { super(OP_IF,ctrl,pred); }

  @Override public Node ideal_reduce() {
    Node ctl = in(0);
    Node tst = in(1);
    if( ctl._val == Type.XCTRL && in(1)!=Env.ANY )
      return set_def(1,Env.ANY); // Kill test; control projections fold up other ways
    // Binary test vs 0?
    if( tst._defs._len==3 &&
        (tst.val(1)==TypeNil.NIL || tst.val(2)==TypeNil.NIL) ) {
      // Remove leading test-vs-0
      if( tst instanceof PrimNode.EQ_I64 ) throw AA.unimpl();
      if( tst instanceof PrimNode.EQ_F64 ) throw AA.unimpl();
      if( tst instanceof PrimNode.EQ_OOP ) throw AA.unimpl();

      // Remove leading negation-vs-0 by inverting
      if( tst instanceof PrimNode.NE_I64 ) throw AA.unimpl();
      if( tst instanceof PrimNode.NE_F64 ) throw AA.unimpl();
      if( tst instanceof PrimNode.NE_OOP ) throw AA.unimpl();
    }

    if( tst instanceof PrimNode.NotI64 && tst._uses._len==1 )
      //return flip(Env.GVN.xreduce(new IfNode(ctl,tst.in(ARG_IDX))));
      throw unimpl();

    return null;
  }
  Node flip(Node that) {
    ProjNode p0 = (ProjNode)_uses.atX(0);
    ProjNode p1 = (ProjNode)_uses.atX(1);
    if( p0==null || p1==null ) return null; // Not well formed
    if( p0._idx==1 ) { ProjNode tmp=p0; p0=p1; p1=tmp; }
    //Node x0 = Env.GVN.xreduce(new CProjNode(that,0));
    //Node x1 = Env.GVN.xreduce(new CProjNode(that,1));
    //p0.subsume(x1);
    //p1.subsume(x0);
    //x0._live = x1._live = that._live = this._live;
    //return that;
    throw unimpl();
  }

  // Some CSE folded my input, extra Casts might optimize
  @Override public void add_flow_use_extra(Node chg) {
    if( in(1)==chg )
      for( Node uctl : _uses )
        for( Node cast : uctl._uses )
          if( cast instanceof CastNode )
            Env.GVN.add_flow(cast);
  }

  @Override public TypeTuple value() {
    // If the input is exactly zero, we can return false: {ANY,CONTROL}
    // If the input excludes   zero, we can return true : {CONTROL,ANY}
    // If the input excludes   both, we can return ANY:   {ANY,ANY}
    // If the input includes   both, we can return both:  {CONTROL,CONTROL}
    Type ctrl = val(0);
    if( ctrl!=Type.CTRL && ctrl != Type.ALL ) return TypeTuple.IF_ANY; // Test is dead
    if( in(0) instanceof ProjNode && in(0).in(0)==this )
      return TypeTuple.IF_ANY; // Test is dead cycle of self (during collapse of dead loops)
    Type pred = val(1);
    if( !(pred instanceof TypeNil tn) ) return (TypeTuple)pred.oob(TypeTuple.IF_ALL);
    if( tn._nil ) return tn._sub ? TypeTuple.IF_ANY  : TypeTuple.IF_FALSE;
    else          return tn._sub ? TypeTuple.IF_TRUE : TypeTuple.IF_ALL;
  }

  private static boolean falsey( Type t ) {
    return t == TypeNil.NIL || t == TypeNil.XNIL;
  }

  @Override public boolean has_tvar() { return false; }

  @Override public Node is_copy(int idx) {
    if( is_prim() ) return null;
    if( !(_val instanceof TypeTuple tt) ) return null;
    if( tt==TypeTuple.IF_ANY ) return Env.XCTRL;
    if( tt==TypeTuple.IF_TRUE  && idx==1 ) return in(0);
    if( tt==TypeTuple.IF_FALSE && idx==0 ) return in(0);
    return null;
  }
}
