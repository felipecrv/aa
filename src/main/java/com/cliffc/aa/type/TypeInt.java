package com.cliffc.aa.type;

import com.cliffc.aa.util.*;

import java.util.HashMap;

public class TypeInt extends TypeNil<TypeInt> {
  // If z==0, then _con has the constant, and the bitsize comes from that.
  // Constant int 0 has zero bits then.
  // If z!=0, then _con is zero and unused; z represents the range.
  // Bit ranges are 1,8,16,32,64
  // _any dictates high or low
  public  byte _z;        // bitsiZe, one of: 1,8,16,32,64
  private long _con;      // constant
  private TypeInt init(boolean any, boolean nil, boolean sub, int z, long con ) {
    super.init(any,nil,sub,BitsAlias.EMPTY,BitsFun.EMPTY);
    _z=(byte)z;
    _con = con;
    return this;
  }

  @Override protected TypeInt copy() {
    TypeInt ti = super.copy();
    ti._z = _z;
    ti._con = _con;
    return ti;
  }
  // Hash does not depend on other types
  @Override long static_hash() { return Util.mix_hash(super.static_hash(),_z,(int)_con); }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeInt t2) ) return false;
    return super.equals(o) && _z==t2._z && _con==t2._con;
  }
  @Override SB _str0( VBitSet visit, NonBlockingHashMapLong<String> dups, SB sb, boolean debug, boolean indent ) {
    if( _z==0 )
      return sb.p(_con);
    return _strn(sb).p("int").p(_z);
  }

  static TypeInt valueOfInt(String cid) {
    if( cid==null ) return null;
    return switch(cid) {
    case   "int1"  ->  BOOL ;
    case   "int8"  ->  INT8 ;
    case   "int16" ->  INT16;
    case   "int32" ->  INT32;
    case   "int64" ->  INT64;
    case  "nint8"  -> NINT8 ;
    case  "nint64" -> NINT64;
    default       -> null;
    };
  }
  static { new Pool(TINT,new TypeInt()); }
  public static TypeInt make( boolean any, boolean nil, boolean sub, int z, long con ) {
    TypeInt t1 = POOLS[TINT].malloc();
    return t1.init(any,nil,sub,z,con).canonicalize().hashcons_free();
  }
  @Override TypeInt canonicalize() {
    if( _con!=0 ) {
      assert !_any && !_nil;
      if( !_sub ) { _z=(byte)log(_con); _con=0; } // constant plus zero is no longer a constant
    }
    return this;
  }

  public static TypeInt con(long con) { return make(false,false,true,0,con); }

  public  static final TypeInt NINT64= make(false,false, true,64,0);
  public  static final TypeInt INT64 = make(false,false,false,64,0);
  public  static final TypeInt INT32 = make(false,false,false,32,0);
  public  static final TypeInt INT16 = make(false,false,false,16,0);
  public  static final TypeInt INT8  = make(false,false,false, 8,0);
  public  static final TypeInt NINT8 = make(false,false,true , 8,0);
  public  static final TypeInt BOOL  = make(false,false,false, 1,0);
  public  static final TypeInt ZERO  = con(0);
  public  static final TypeInt TRUE  = con(1);
  public  static final TypeInt C3    = con(3);
  public  static final TypeInt C123  = con(123456789L);
  static final TypeInt[] TYPES = new TypeInt[]{INT64,NINT64,INT32,INT16,INT8,NINT8,BOOL,TRUE,C3,C123};
  public static void init1( HashMap<String,TypeNil> types ) {
    types.put("bool" ,BOOL);
    types.put("int1" ,BOOL);
    types.put("int8" ,INT8);
    types.put("int16",INT16);
    types.put("int32",INT32);
    types.put("int64",INT64);
    types.put("int"  ,INT64);
  }
  // Return a long from a TypeInt constant; assert otherwise.
  @Override public long   getl() { assert is_con(); return _con; }
  @Override public double getd() { assert is_con() && (long)((double)_con)==_con; return _con; }

  @Override protected TypeInt xdual() {
    if( _z==0 ) return this;
    TypeInt x = super.xdual();
    x._z = _z;
    x._con = 0;
    return x;

  }
  @Override protected TypeInt xmeet( Type t ) {
    TypeInt ti = (TypeInt)t;
    TypeInt rez = ymeet(ti);
    rez._con = 0;
    if( !rez._any ) {
      int lz0 =    _z==0 ? log(   _con) :    _z;
      int lz1 = ti._z==0 ? log(ti._con) : ti._z;
      if(    _z==0 && ti._any && (ti._nil || ti._sub) && lz0 <= lz1 ) return rez.free(this); // Keep a constant
      if( ti._z==0 &&    _any && (   _nil ||    _sub) && lz1 <= lz0 ) return rez.free(ti  ); // Keep a constant
      rez._z = (byte)(_any ? lz1 : (ti._any ? lz0 : Math.max(lz0,lz1)));
    } else {
      // Both are high, not constants.  Narrow size.
      rez._z = (byte)Math.min(_z,ti._z);
    }
    return rez.hashcons_free();
  }

  private static int log( long con ) {
    if(     0 <= con && con <=     1 ) return  1;
    if(  -128 <= con && con <=   127 ) return  8;
    if(-32768 <= con && con <= 32767 ) return 16;
    if( Integer.MIN_VALUE <= con && con <= Integer.MAX_VALUE ) return 32;
    return 64;
  }

  @Override public TypeInt widen() { return INT64; }
  
  @Override TypeNil cross_flt(TypeNil f) {
    if( !(f instanceof TypeFlt flt) ) return null;
    // If the float is high, inject it into the next smaller high int  .
    // If the int   is high, inject it into the smallest     low float.
    // If the int   is low , inject it into the next larger  low  float.
    if( flt._any )  return TypeInt.make(flt._any,flt._nil,flt._sub,flt._z>>1,0).xmeet(this);
    int z = _z==0 ? log(_con) : _z;
    if( !_any && z==64 ) return null;    
    return TypeFlt.make(false,_nil,_sub,_any || z<32 ? 32 : 64,_con).xmeet(flt);
  }

  
  @Override public boolean is_con()  { return _z==0; }
  public TypeInt minsize(TypeInt ti) {
    int zs =    _z==0 ? log(   _con) :    _z;
    int zi = ti._z==0 ? log(ti._con) : ti._z;
    return make(false, false, false, Math.min(zs,zi), 0);
  }
  public TypeInt maxsize(TypeInt ti) { return (TypeInt)meet(ti);  }
}
