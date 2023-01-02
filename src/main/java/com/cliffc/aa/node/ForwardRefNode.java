package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.ErrMsg;
import com.cliffc.aa.Parse;
import com.cliffc.aa.tvar.TV3;
import com.cliffc.aa.type.*;

import static com.cliffc.aa.AA.*;

// A forward ref in the parser.
public class ForwardRefNode extends Node {
  public final String _name;   // Name of forward ref
  Parse _bad;                  // Where it first appears
  // Forward Ref moves through 3-states:
  // - 0 forward-ref; scope is not known; can add defs?
  // - 1 scoped; scope is known; can add defs?
  // - 2 defined; scope is known and complete, no more adding defs
  private byte _fref;
  public ForwardRefNode( String name, Parse bad ) { super(OP_FREF); _name = name; _bad=bad; _val = TypeFunPtr.GENERIC_FUNPTR; add_def(null); }

  @Override public String xstr() {
    if( is_dead() ) return "DEAD";
    if( _defs._len==0 ) return "???"+_name;
    String s = switch( _fref ) {
    case 0 -> "???";
    case 1 -> "??";
    default -> "?";
    };
    return s+_name;
  }

  @Override public Type value() {
    if( is_forward_ref() ) return TypeFunPtr.GENERIC_FUNPTR;
    throw unimpl();
  }

  @Override public boolean has_tvar() { return true; }
  
  // True if this is a forward_ref
  public boolean is_forward_ref() { return _defs._len==0 || _fref<=1; }
  // One-time flip _fref, no longer a forward ref
  public ForwardRefNode scoped() { assert _fref==0; _fref=1; return this; }
  public ForwardRefNode define() { assert _fref==1; _fref=2; return this; }
  boolean is_scoped () { return _fref==1; }
  boolean is_defined() { return _fref==2; }

  @Override public int hashCode() { return super.hashCode()+(_bad==null ? 0 : _bad.hashCode()); }
  @Override public boolean equals(Object o) {
    if( !super.equals(o) ) return false;
    return _bad==((ForwardRefNode)o)._bad;
  }

  // Assigning the forward-ref removes the error
  @Override public ErrMsg err( boolean fast ) {  return ErrMsg.forward_ref(_bad,_name);  }

}
