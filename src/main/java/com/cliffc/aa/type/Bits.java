package com.cliffc.aa.type;

import com.cliffc.aa.AA;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.SB;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

// Bits supporting a lattice; immutable; hash-cons'd.  Bits can be *split* in
// twain, and the matching pair is every equivalent to the unsplit bit.
// Splitting is useful during e.g. inlining, where a single Call is duplicated
// and RPCs to the original one might return to either of of the inlines.  Same
// for internal functions and allocation sites - after the inline, pointers &
// references to the original might now refer to either copy.  Each copy only
// refers to itself, so after some optimizations the ambiguious bits can be
// optimized away.  i.e., its useful to make the distinction between the cloned
// instances, just might be some confusion at first.
//
// Bit 0 - is always the 'null' or 'empty' instance.
// Bit 1 - is the first "real" bit, and represents all-of-memory.
// Other bits always split from bit 1, and can split in any pattern.  This
// pattern is held in a TypeTree which must be passed in for canonicalization.
public abstract class Bits<B extends Bits<B>> implements Iterable<Integer> {
  // Holds a set of bits meet'd together, or join'd together, along
  // with an infinite extent or a single bit choice as a constant.
  //
  // If _bits is NULL, then _con holds the single set bit (including 0).
  // If _bits is not-null, then _con is -2 for meet, and -1 for join.
  private long[] _bits;   // Bits set or null for a single bit
  private int _con;       // value of single bit, or -2 for meet or -1 for join
  int _hash;      // Pre-computed hashcode
  void init(int con, long[] bits ) {
    _con = con;
    _bits=bits;
    assert check();
    _hash=compute_hash();
  }
  private boolean check() {
    if( _bits==null ) return _con >= 0; // Must be a single constant bit#
    if( _con != -2 && _con != -1 ) return false;
    if( _bits.length==0 ) return false;
    // TODO: if a parent is set, then all children are cleared.
    // If the parent is clear and closed, then at least one child is cleared.
    // Requires the matching TypeTree.
    return true;
  }
  int compute_hash() {
    int sum= _con;
    if( _bits != null ) for( long bit : _bits ) sum += bit;
    return sum;
  }
  @Override public int hashCode( ) { return _hash; }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof Bits) ) return false;
    Bits bs = (Bits)o;
    if( _con != bs._con || _hash != bs._hash ) return false;
    if( _bits == bs._bits ) return true;
    if( _bits ==null || bs._bits==null ) return false;
    if( _bits.length != bs._bits.length ) return false;
    for( int i=0; i<_bits.length; i++ )
      if( _bits[i]!=bs._bits[i] ) return false;
    return true;
  }
  @Override public String toString() { return toString(new SB()).toString(); }
  public SB toString(SB sb) {
    if( this==FULL() ) return sb.p("[ALL]");
    else if( _con==-1 && _bits.length==1 && _bits[0]==-1 ) return sb.p("[~ALL]");
    sb.p('[');
    if( _bits==null ) sb.p(_con);
    else {
      for( Integer idx : this ) sb.p(idx).p(above_center()?'+':',');
    }
    return sb.p(']');
  }

  // Intern: lookup and return an existing Bits or install in hashmap and
  // return a new Bits.  Overridden in subclasses to make type-specific Bits.
  abstract Bits<B> make_impl(int con, long[] bits );

  // Constructor taking an array of bits, and allowing join/meet selection.
  // Canonicalizes the bits.  The 'this' pointer is only used to clone the class.
  final Bits<B> make( int con, long[] bits, Ary<TypeTree> trees ) {
    int len = bits.length;

    // Verify proper tree structure: if parent is set, then all children are
    // clear; if parent is clear and closed, and all children are set - then
    // the parent can replace the children.
    if( trees != null ) { // Canonicalization is skipped for various initialization cases
      // Build an iterator over the bits that does not allocate
      for( int idx=0; idx<len; idx++ ) {
        // Walk the bits from lowest to highest, looking only at set bits
        for( long b = bits[idx]; b != 0; b = b&(b-1)/*clears low bit*/ ) {
          long bit = (b&(b-1))^b;   // Lowest bit
          int bnum = Long.numberOfTrailingZeros(bit);
          int i = (idx<<6)+bnum; // Set bit number
          if( i==0 ) continue;   // Ignore nil
          TypeTree t = trees.at(i);
          TypeTree par = t._par;
          if( par != null && (bits[idx(par._idx)]&mask(par._idx))!=0 ) { // Parent is set?
            bits[idx] &= ~bit;  // Parent is set, so clear kid
          } else {              // Parent is clear
            if( par != null && par.closed() && par._kids != null ) {
              // Parent is clear and closed.  See if all kids are set.
              boolean all_set = true;
              for( TypeTree kid : par._kids )
                if( (bits[idx(kid._idx)]&mask(kid._idx))==0 )
                  { all_set=false; break; }
              if( all_set )     // All set; instead set parent and clear this kid (and all future kids)
                throw AA.unimpl();
            }
          }
        }
      }
    }
    
    // Remove any trailing empty words
    while( len > 0 && (bits[len-1]==0 || bits[len-1]== -1) ) len--;
    if( bits.length != len ) bits = Arrays.copyOf(bits,len);

    // Check for a single bit
    long b = bits[len-1];
    if( (b & (b-1))!=0 )
      return make_impl(con,bits); // Multiple bits
    // Last word has only a single bit
    for( int i=0; i<len-1; i++ )
      if( bits[i] != 0 )
        return make_impl(con,bits); // Multiple bits spread over multiple words
    con = 63-Long.numberOfLeadingZeros(b);
    return make_impl(con,null); // Single bit in last word only, switch to con version
  }
  // Constructor taking a list of bits; bits are 'meet'.
  final Bits make( Ary<TypeTree> trees, int... bits ) {
    long[] ls = new long[1];
    for( long bit : bits ) {
      if( bit < 0 ) throw new IllegalArgumentException("bit must be positive");
      if( bit >= 63 ) throw AA.unimpl();
      ls[0] |= 1L<<bit;
    }
    return make(-2,ls,trees);
  }
  // Constructor taking a single bit
  final Bits make( int bit ) {
    if( bit < 0 ) throw new IllegalArgumentException("bit must be positive");
    return make_impl(bit,null);
  }

  public abstract Bits<B> FULL();
  public abstract Bits<B> ANY ();
  
  private static int  idx (long i) { return (int)(i>>6); }
  private static long mask(long i) { return 1L<<(i&63); }
  
  int getbit() { assert _bits==null; return _con; }
  public int abit() { return _bits==null ? _con : -1; }
  public boolean is_con() { return _bits==null; }
  public boolean above_center() { return _con==-1; }
  boolean may_nil() { return _con==0 || (_con==-1 && ((_bits[0]&1) == 1)); }

  // Test a specific bit is set or clear
  public boolean test(int i) {
    if( _bits==null ) return i==_con;
    int idx = idx(i);
    return idx < _bits.length ? (_bits[idx]&mask(i))!=0 : false;
  }
  public Bits clear(int i) {
    if( !test(i) ) return this;  // Already clear
    if( _con==i ) return make(null); // No bits set???
    assert _con<0;
    int idx = idx(i);
    long[] bits = _bits.clone();
    bits[idx] &= ~mask(i);
    return make(_con,bits,null);
  }
  
  private int max() { return (_bits.length<<6)-1; }
  private static void or ( long[] bits, long con ) { bits[idx(con)] |= mask(con); }
  private static long[] bits( long a, long b ) { return new long[idx(Math.max(a,b))+1]; }

  public Bits<B> meet( Bits<B> bs, Ary<TypeTree> trees ) {
    if( this==bs ) return this;
    Bits<B> full = FULL();         // Subclass-specific version of full
    if( this==full || bs==full ) return full;
    Bits any  = ANY ();         // Subclass-specific version of any
    if( this==any ) return bs;
    if( bs  ==any ) return this;
    if( _bits==null || bs._bits==null ) { // One or both are constants
      Bits<B> conbs = this, bigbs = bs;
      if( bs._bits==null ) { conbs = bs;  bigbs = this; }
      if( bigbs._bits==null ) { // both constants
        // two constants; make a big enough bits array for both
        long[] bits = bits(conbs._con,bigbs._con);
        or( bits,conbs._con);
        or( bits,bigbs._con);
        return make(-2,bits,trees);
      }
      
      if( bigbs._con==-2 ) {     // Meet of constant and set
        if( bigbs.test(conbs._con) ) return bigbs; // already a member
        // Grow set to hold constant and OR it in
        long[] bits = bits(bigbs.max(),conbs._con);
        System.arraycopy(bigbs._bits,0,bits,0,bigbs._bits.length);
        or( bits,conbs._con);
        return make(-2,bits,trees);
      }
      // Meet of constant and joined set
      if( bigbs.test(conbs._con) ) return conbs; // Already a member
      // Pick first non-zero bit, and meet with constant
      if( conbs._con >= 64 ) throw AA.unimpl();
      for( int e : bigbs )
        if( e != 0 ) {
          if( e >= 64 ) throw AA.unimpl();
          return make(-2,new long[]{(1L<<e) | (1L<<conbs._con)},trees);
        }
      throw AA.unimpl(); // Should not reach here
    }

    if( _con==-2 ) {            // Meet
      if( bs._con==-2 ) {
        Bits smlbs = this, bigbs = bs;
        if( smlbs._bits.length > bigbs._bits.length ) { smlbs=bs; bigbs=this; }
        long[] bits = bigbs._bits.clone();
        for( int i=0; i<smlbs._bits.length; i++ ) bits[i]|=smlbs._bits[i];
        return make(-2,bits,trees);

      } else {                  // Meet of a high set and low set
        // TODO: Require 1 bit from high set in the low set.
        // For now, just return low set
        return this;
      }
    }
    if( bs._con==-2 ) { // Meet of a low set and high set
      // TODO: Require 1 bit from high set in the low set.
      // For now, just return low set
      return bs;
    }

    // join of 2 sets; return intersection
    if(    subset(bs  ) ) return this;
    if( bs.subset(this) ) return bs  ;

    // join of 2 joined sets.  OR all bits together.
    Bits smlbs = this, bigbs = bs;
    if( smlbs._bits.length > bigbs._bits.length ) { smlbs=bs; bigbs=this; }
    long[] bits =  bigbs._bits.clone();
    for( int i=0; i<smlbs._bits.length; i++ )
      bits[i] |= smlbs._bits[i];
    return make(-1,bits,trees);
  }
  
  private boolean subset( Bits bs ) {
    if( _bits.length > bs._bits.length ) return false;
    for( int i=0; i<_bits.length; i++ )
      if( (_bits[i]&bs._bits[i]) != _bits[i] )
        return false;
    return true;
  }
  
  public Bits<B> dual() {
    if( _bits==null ) return this; // Dual of a constant is itself
    // Otherwise just flip _con
    assert _con==-2 || _con==-1;
    return make_impl(-3-_con,_bits);
  }
  // join is defined in terms of meet and dual
  public Bits<B> join(Bits<B> bs, Ary<TypeTree> trees) { return dual().meet(bs.dual(),trees).dual(); }

  /** @return an iterator */
  @NotNull @Override public Iterator<Integer> iterator() { return new Iter(); }
  private class Iter implements Iterator<Integer> {
    int _i=-1;
    @Override public boolean hasNext() {
      if( _bits==null )
        if( _i==-1 ) { _i=0; return true; } else return false;
      int idx;
      while( (idx=idx(++_i)) < _bits.length )
        if( (_bits[idx]&mask(_i)) != 0 )
          return true;
      return false;
    }
    @Override public Integer next() {
      if( _bits==null ) return _con;
      if( idx(_i) < _bits.length ) return _i;
      throw new NoSuchElementException();
    }
  }
}
