package com.cliffc.aa.type;

import com.cliffc.aa.AA;
import com.cliffc.aa.util.SB;

import java.util.Arrays;
import java.util.BitSet;

/**
   Memory type; the state of all of memory; memory edges order memory ops.
   Produced at the program start, consumed by all function calls, consumed be
   Loads, consumed and produced by Stores.  Can be broken out in the "equiv-
   alence class" (Alias#) model of memory over a bulk memory to allow more fine
   grained knowledge.  Memory is accessed via Alias#s, where all TypeObjs in an
   Alias class are Meet together as an approximation.

   Conceptually, each alias# represents an infinite set of pointers - broken
   into equivalence classes.  We can split such a class in half - some pointers
   will go left and some go right, and where we can't tell we'll use both sets.
   Any alias set is a tree-like nested set of sets bottoming out in individual
   pointers.  The types are conceptually unchanged if we start using e.g. 2
   alias#s instead of 1 everywhere - we've just explicitly named the next layer
   in the tree-of-sets.

   Split an existing alias# in half, such that some ptrs point to one half or
   the other, and most point to either (or both).  Basically find all
   references to alias#X and add a new alias#Y paired with X - making all
   alias types use both equally.  Leave the base constructor of an X alias
   (some NewNode) alone - it still produces just an X.  The Node calling
   split_alias gets Y alone, and the system as a whole makes a conservative
   approximation that {XY} are always confused.  Afterwards we can lift the
   types to refine as needed.

   During iter()/pessimistic-GVN we'll have ptrs to a single New which splits -
   and this splits the aliases; repeated splitting induces a tree.  Some ptrs
   to the tree-root will remain, and represent conservative approximation as
   updates to outputs from all News.  We'll also have sharper direct ptrs
   flowing out, pointing to only results from a single New.  At the opto()
   point we'll not have any more confused types.

   Memory is supposed to be everywhere precise - but an "all-memory" notion is
   used to handle the worse-case from e.g. all unknown calls.  Really the worse
   a Call can be is to "leak" all aliases that come in to the the call - but we
   need a convenient Bottom type.  Missing aliases default to TypeObj.

   CNC - Observe that the alias Trees on Fields applies to Indices on arrays as
   well - if we can group indices in a tree-like access pattern (obvious one
   being All vs some Constants).
*/
public class TypeMem extends Type<TypeMem> {
  private boolean _any;
  // Mapping from alias#s to the current known alias state.  Slot 0 is
  // reserved; TypeMem is never a nil.  Slot#1 is the Parent-Of-All aliases and
  // is the default value.  Default values are replaced with null during
  // canonicalization.
  private TypeObj[] _aliases;
  
  private TypeMem  (boolean any, TypeObj[] aliases) { super(TMEM); init(any,aliases); }
  private void init(boolean any, TypeObj[] aliases) {
    super.init(TMEM);
    _any = any;
    _aliases = aliases;
    assert check();
  }
  // False if not 'tight' (no trailing null pairs) or any matching pairs (should
  // collapse to their parent) or any mixed parent/child.
  private boolean check() {
    TypeObj[] as = _aliases;
    if( as.length == 0 ) return true;
    if( as[0]!=null ) return false;          // Slot 0 reserved
    if( as[1]!=TypeObj.OBJ && as[1]!=TypeObj.XOBJ && as[1] != null )
      return false;             // Only 2 choices
    if( as.length==2 ) return true; // Trivial all of memory
    // "tight" - something in the last slot
    if( _aliases[_aliases.length-1] == null ) return false;
    // No dups of the default in slot 1
    for( int i=2; i<as.length; i++ )
      if( as[i]==as[1] )
        return false;
    return true;
  }
  @Override int compute_hash() {
    int hash = TMEM + (_any?1:0);
    for( TypeObj obj : _aliases )  if( obj != null )  hash += obj._hash;
    return hash;
  }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeMem) ) return false;
    TypeMem tf = (TypeMem)o;
    if( _any!=tf._any || _aliases.length != tf._aliases.length ) return false;
    for( int i=0; i<_aliases.length; i++ )
      if( _aliases[i] != tf._aliases[i] ) // note '==' and NOT '.equals()'
        return false;
    return true;
  }
  // Never part of a cycle, so the normal check works
  @Override public boolean cycle_equals( Type o ) { return equals(o); }
  @Override String str( BitSet dups ) {
    if( this== MEM ) return "[mem]";
    if( this==XMEM ) return "[~mem]";
    SB sb = new SB();
    if( _any ) sb.p('~');
    sb.p('[');
    for( int i=1; i<_aliases.length; i++ )
      if( _aliases[i] != null )
        sb.p(i).p("#:").p(_aliases[i].toString()).p(",");
    return sb.p(']').toString();
  }
                
  // Alias-at.  Now never returns a null.  Prior versions go back-n-forth on
  // whether or not this call returns a null.
  public TypeObj at0(int alias) { return alias < _aliases.length ? _aliases[alias] : null;  }
  // Alias-at, but defaults to "XOBJ" for easy meet() calls.
  // Never returns a null.
  public TypeObj at(int alias) {
    TypeObj obj = at0(alias);
    return obj==null ? TypeObj.XOBJ : obj;
  }
  
  private static TypeMem FREE=null;
  @Override protected TypeMem free( TypeMem ret ) { _aliases=null; FREE=this; return ret; }
  private static TypeMem make(boolean any, TypeObj[] aliases) {
    TypeMem t1 = FREE;
    if( t1 == null ) t1 = new TypeMem(any, aliases);
    else { FREE = null;       t1.init(any, aliases); }
    TypeMem t2 = (TypeMem)t1.hashcons();
    return t1==t2 ? t1 : t1.free(t2);
  }

  // Canonicalize memory before making.  Unless specified, the default memory is "dont care"
  static TypeMem make0( boolean any, TypeObj[] as ) {
    if( as[1]==null ) as[1] = TypeObj.XOBJ; // Default memory is "dont care"
    int len = as.length;
    if( len == 2 ) return make(any,as);
    // No dups of default in slot 1
    for( int i=2; i<len; i++ )
      if( as[i]==as[1] )
        as[i]=as[1];
    // Remove trailing nulls; make the array "tight"
    while( as[len-1] == null ) len--;
    if( as.length!=len ) as = Arrays.copyOf(as,len);

    return make(any,as);
  }

  // Precise single alias.  Other aliases are "dont care".  Nil not allowed.
  public static TypeMem make(int alias, TypeObj oop ) {
    TypeObj[] as = new TypeObj[alias+1];
    as[BitsAlias.ALL] = TypeObj.XOBJ; // Everything else is "dont care"
    as[alias] = oop;
    return make(false,as);
  }

  public  static final TypeMem  MEM = make(false,new TypeObj[]{null,TypeObj. OBJ}); // Every alias filled with something
  public  static final TypeMem XMEM = make(false,new TypeObj[]{null,TypeObj.XOBJ}); // Every alias filled with anything
  public  static final TypeMem EMPTY_MEM = XMEM; //make(new TypeObj[0]); // Tried no-memory-vs-XOBJ-memory
          static final TypeMem MEM_STR = make(BitsAlias.STR,TypeStr.STR);
          static final TypeMem MEM_ABC = make(BitsAlias.STR,TypeStr.ABC);
  static final TypeMem[] TYPES = new TypeMem[]{MEM,MEM_STR};

  // All mapped memories remain, but each memory flips internally.
  @Override protected TypeMem xdual() {
    TypeObj[] oops = new TypeObj[_aliases.length];
    for(int i=0; i<_aliases.length; i++ )
      if( _aliases[i] != null )
        oops[i] = (TypeObj)_aliases[i].dual();
    return new TypeMem(!_any,oops);
  }
  @Override protected Type xmeet( Type t ) {
    if( t._type != TMEM ) return ALL; //
    TypeMem tf = (TypeMem)t;

    // Meet of default values, meet of element-by-element.
    int len = Math.max(_aliases.length,tf._aliases.length);
    TypeObj[] objs = new TypeObj[len];
    for( int i=0; i<len; i++ )
      if( (i<   _aliases.length &&    _aliases[i] != null) ||
          (i<tf._aliases.length && tf._aliases[i] != null) ) // short-cut for both null
        objs[i] = (TypeObj)at(i).meet(tf.at(i));             // meet element-by-element
    return make0(_any&&tf._any,objs);
  }

  // Meet of all possible loadable values
  public TypeObj ld( TypeMemPtr ptr ) {
    boolean any = ptr.above_center();
    TypeObj obj = any ? TypeObj.OBJ : TypeObj.XOBJ;
    for( int alias : ptr._aliases ) {
      if( alias != 0 ) {        // nil on a JOIN is ignored, on a MEET is failure
        TypeObj x = at(alias);
        obj = (TypeObj)(any ? obj.join(x) : obj.meet(x));
      }
    }
    return obj;
  }

  // Meet of all possible storable values, after updates
  public TypeMem st( TypeMemPtr ptr, String fld, int fld_num, Type val ) {
    assert val.isa_scalar();
    //TypeObj[] objs = new TypeObj[_aliases.length];
    //for( int alias : ptr._aliases )
    //  objs[alias] = at0(alias).update(fld,fld_num,val);
    //return make0(_any,objs);
    throw AA.unimpl();
  }

  // Merge two memories with no overlaps.  This is similar to a st(), except
  // updating an entire Obj not just a field, and not a replacement.  The
  // given memory is precise.
  public TypeMem merge( TypeMem mem ) {
    // Given memory must be "skinny", only a single alias.
    TypeObj[] ms = mem._aliases;
    int mlen = ms.length;
    int alias = mlen-1;
    TypeObj obj = ms[alias];
    assert alias >= 1 && obj != null;
    for( int i=2; i<alias; i++ )  assert ms[i]==null;

    TypeObj[] objs = Arrays.copyOf(_aliases,Math.max(_aliases.length,alias+1));
    objs[alias]=obj;
    return make0(_any,objs);
  }
  
  @Override public boolean above_center() { return _any; }
  @Override public boolean may_be_con()   { return false;}
  @Override public boolean is_con()       { return false;}
  @Override public boolean must_nil() { return false; } // never a nil
  @Override Type not_nil() { return this; }
}
