package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.tvar.TV2;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.NonBlockingHashMap;

import static com.cliffc.aa.AA.*;
import static com.cliffc.aa.Env.GVN;

// See CallNode.  Slot 0 is the Call.  The remaining slots are Returns which
// are typed as standard function returns: {Ctrl,Mem,Val}.  These Returns
// represent call-graph edges that are known to be possible and thus their fidx
// appears in the Call's BitsFun type.
//
// Pre-opto it is possible for the all-functions type to appear at a Call, and
// in this case the CallEpi must assume all possible calls may happen, they are
// just not wired up yet.
//
// It is also possible that we have discovered and wired up a function, but
// that the input/output types are not yet monotonic, and we do not flow values
// across those edges until the types align.  This is controlled with a small
// bit-vector.

public final class CallEpiNode extends Node {
  public boolean _is_copy;
  public CallEpiNode( Node... nodes ) {
    super(OP_CALLEPI,nodes);
    assert nodes[1] instanceof DefMemNode;
  }
  @Override public String xstr() {// Self short name
    if( _is_copy ) return "CopyEpi";
    if( is_dead() ) return "XallEpi";
    return "CallEpi";
  }
  public CallNode call() { return (CallNode)in(0); }
  @Override public boolean is_mem() { return true; }
  public int nwired() { return _defs._len-2; }
  public RetNode wired(int x) { return (RetNode)in(x+2); }

  @Override public Node ideal_reduce() {
    if( _is_copy ) return null;
    CallNode call = call();
    Type tc = call._val;
    if( !(tc instanceof TypeTuple) ) return null;
    TypeTuple tcall = (TypeTuple)tc;
    if( CallNode.tctl(tcall) != Type.CTRL ) return null; // Call not executable
    // Get calls resolved function.
    BitsFun fidxs = CallNode.ttfp(tcall).fidxs();

    // If the call's allowed functions excludes any wired, remove the extras
    if( !fidxs.test(BitsFun.ALL) ) { // Shortcut
      for( int i = 0; i < nwired(); i++ ) {
        RetNode ret = wired(i);
        if( !fidxs.test_recur(ret.fidx()) ) { // Wired call not in execution set?
          assert !BitsFun.is_parent(ret.fidx());
          // Remove the edge.  Pre-GCP all CG edges are virtual, and are lazily
          // and pessimistically filled in by ideal calls.  During the course
          // of lifting types, some manifested CG edges are killed.
          // Post-GCP all CG edges are manifest, but types can keep lifting
          // and so CG edges can still be killed.
          unwire(call,ret);
          return this; // Return with progress
        }
      }
    }

    // See if we can wire any new fidxs directly between Call/Fun and Ret/CallEpi.
    // This *adds* edges, but enables a lot of shrinking via inlining.
    if( check_and_wire(Env.GVN._work_flow) ) return this;

    // The one allowed function is already wired?  Then directly inline.
    // Requires this calls 1 target, and the 1 target is only called by this.
    if( nwired()==1 && fidxs.abit() != -1 ) { // Wired to 1 target
      RetNode ret = wired(0);                 // One wired return
      FunNode fun = ret.fun();
      Type tdef = Env.DEFMEM._uses._len==0 ? null : Env.DEFMEM._val;
      TypeTuple tret = ret._val instanceof TypeTuple ? (TypeTuple) ret._val : (TypeTuple)ret._val.oob(TypeTuple.RET);
      Type tretmem = tret.at(1);
      if( fun != null && fun._defs._len==2 && // Function is only called by 1 (and not the unknown caller)
          (call.err(true)==null || fun._thunk_rhs) &&       // And args are ok
          (tdef==null || GVN._opt_mode._CG || CallNode.emem(tcall).isa(tdef)) && // Pre-GCP, call memory has to be as good as the default
          (tdef==null || GVN._opt_mode._CG || tretmem.isa(tdef) ) &&  // Call and return memory at least as good as default
          call.mem().in(0) != call &&   // Dead self-recursive
          fun.in(1)._uses._len==1 &&    // And only calling fun
          ret._live.isa(_live) &&       // Call and return liveness compatible
          !fun.noinline() ) {           // And not turned off
        assert fun.in(1).in(0)==call;   // Just called by us
        int idx = Env.SCP_0._defs.find(ret);
        if( idx!=-1 ) Env.SCP_0.del(idx);
        fun.set_is_copy();              // Collapse the FunNode into the Call
        if( fun._name!=null && fun._name.charAt(0)=='$' ) { // Inlining a primitive into a wrapper from _prims.aa
          FunNode outer_fun = (FunNode)call.ctl();
          // Copy the op_prec up 1 layer
          // TODO: Make an official user-mode operator syntax, and put it in _prims.aa
          outer_fun._op_prec = fun._op_prec;
          outer_fun._thunk_rhs = fun._thunk_rhs;
        }
        return set_is_copy(ret.ctl(), ret.mem(), ret.rez()); // Collapse the CallEpi into the Ret
      }
    }

    // Parser thunks eagerly inline
    if( call.fdx() instanceof ThretNode ) {
      ThretNode tret = (ThretNode)call.fdx();
      wire1(Env.GVN._work_flow,call,tret.thunk(),tret);
      return set_is_copy(tret.ctrl(), tret.mem(), tret.rez()); // Collapse the CallEpi into the Thret
    }

    // Only inline wired single-target function with valid args.  CallNode wires.
    if( nwired()!=1 ) return null; // More than 1 wired, inline only via FunNode
    int fidx = fidxs.abit();       // Could be 1 or multi
    if( fidx == -1 ) return null;  // Multi choices, only 1 wired at the moment.
    if( fidxs.above_center() ) return null; // Can be unresolved yet
    if( BitsFun.is_parent(fidx) ) return null; // Parent, only 1 child wired

    if( call.err(true)!=null ) return null; // CallNode claims args in-error, do not inline

    // Call allows 1 function not yet inlined, sanity check it.
    int cnargs = call.nargs();
    FunNode fun = FunNode.find_fidx(fidx);
    assert !fun.is_forward_ref() && !fun.is_dead()
      && fun.nargs() == cnargs; // All checked by call.err
    if( fun._val != Type.CTRL ) return null;
    RetNode ret = fun.ret();    // Return from function
    if( ret==null ) return null;

    // Single choice; check no conversions needed.
    TypeStruct formals = fun.formals();
    for( Node parm : fun._uses ) {
      if( parm instanceof ParmNode && parm.in(0)==fun ) {
        int idx = ((ParmNode)parm)._idx;
        Type actual = CallNode.targ(tcall,idx);
        if( idx >= ARG_IDX && actual.isBitShape(formals.fld_idx(idx)._t) == 99 ) // Requires user-specified conversion
          return null;
      }
    }


    // Check for several trivial cases that can be fully inlined immediately.
    Node cctl = call.ctl();
    Node cmem = call.mem();
    Node rctl = ret.ctl();      // Control being returned
    Node rmem = ret.mem();      // Memory  being returned
    Node rrez = ret.rez();      // Result  being returned
    boolean inline = !fun.noinline();
    // If the function does nothing with memory, then use the call memory directly.
    if( (rmem instanceof ParmNode && rmem.in(CTL_IDX) == fun) || rmem._val ==TypeMem.XMEM )
      rmem = cmem;
    // Check that function return memory and post-call memory are compatible
    if( !(_val instanceof TypeTuple) ) return null;
    Type selfmem = ((TypeTuple) _val).at(MEM_IDX);
    if( !rmem._val.isa( selfmem ) /*&& call.is_pure_call()==null*/ )
      return null;

    // Check for zero-op body (id function)
    if( rrez instanceof ParmNode && rrez.in(CTL_IDX) == fun && cmem == rmem && inline )
      return unwire(call,ret).set_is_copy(cctl,cmem,call.arg(((ParmNode)rrez)._idx)); // Collapse the CallEpi

    // Check for constant body
    Type trez = rrez._val;
    if( trez.is_con() && rctl==fun && cmem == rmem && inline )
      return unwire(call,ret).set_is_copy(cctl,cmem,Node.con(trez));

    // Check for a 1-op body using only constants or parameters and no memory effects
    boolean can_inline=!(rrez instanceof ParmNode) && rmem==cmem && inline;
    for( Node parm : rrez._defs )
      if( parm != null && parm != fun &&
          !(parm instanceof ParmNode && parm.in(0) == fun) &&
          !(parm instanceof ConNode) )
        can_inline=false;       // Not trivial
    if( can_inline ) {
      Node irez = rrez.copy(false); // Copy the entire function body
      ProjNode proj = ProjNode.proj(this,REZ_IDX);
      irez._live = proj==null ? TypeMem.ESCAPE : proj._live;
      for( Node parm : rrez._defs )
        irez.add_def((parm instanceof ParmNode && parm.in(CTL_IDX) == fun) ? call.arg(((ParmNode)parm)._idx) : parm);
      if( irez instanceof PrimNode ) ((PrimNode)irez)._badargs = call._badargs;
      GVN.add_work_all(irez);
      return unwire(call,ret).set_is_copy(cctl,cmem,irez);
    }

    return null;
  }

  // Used during GCP and Ideal calls to see if wiring is possible.
  // Return true if a new edge is wired
  public boolean check_and_wire( Work work ) {
    if( !(_val instanceof TypeTuple) ) return false; // Collapsing
    CallNode call = call();
    Type tcall = call._val;
    if( !(tcall instanceof TypeTuple) ) return false;
    BitsFun fidxs = CallNode.ttfp(tcall)._fidxs;
    if( fidxs==BitsFun.FULL ) return false; // Error call
    if( fidxs.above_center() )  return false; // Still choices to be made during GCP.

    // Check all fidxs for being wirable
    boolean progress = false;
    for( int fidx : fidxs ) {                 // For all fidxs
      if( BitsFun.is_parent(fidx) ) continue; // Do not wire parents, as they will eventually settle out
      FunNode fun = FunNode.find_fidx(fidx);  // Lookup, even if not wired
      if( fun==null || fun.is_dead() ) continue; // Already dead, stale fidx
      if( fun.is_forward_ref() ) continue;    // Not forward refs, which at GCP just means a syntax error
      RetNode ret = fun.ret();
      if( ret==null ) continue;               // Mid-death
      if( _defs.find(ret) != -1 ) continue;   // Wired already
      if( !CEProjNode.good_call(tcall,fun) ) continue; // Args fail basic sanity
      progress=true;
      wire1(work,call,fun,ret);      // Wire Call->Fun, Ret->CallEpi
    }
    return progress;
  }

  // Wire the call args to a known function, letting the function have precise
  // knowledge of its callers and arguments.  This adds a edges in the graph
  // but NOT in the CG, until _cg_wired gets set.
  void wire1( Work work, CallNode call, Node fun, Node ret ) {
    assert _defs.find(ret)==-1; // No double wiring
    wire0(work,call,fun);
    // Wire self to the return
    add_def(ret);
    work.add(this);
    work.add(call);
    call.add_work_defs(work);
  }

  // Wire without the redundancy check, or adding to the CallEpi
  void wire0(Work work, CallNode call, Node fun) {
    // Wire.  Bulk parallel function argument path add

    // Add an input path to all incoming arg ParmNodes from the Call.  Cannot
    // assert finding all args, because dead args may already be removed - and
    // so there's no Parm/Phi to attach the incoming arg to.
    for( Node arg : fun._uses ) {
      if( arg.in(0) != fun || !(arg instanceof ParmNode) ) continue;
      int idx = ((ParmNode)arg)._idx;
      Node actual = switch( idx ) {
      case 0 -> new ConNode<>(TypeRPC.make(call._rpc)); // Always RPC is a constant
      case MEM_IDX -> new MProjNode(call, (Env.DEFMEM._uses._len == 0) ? Env.ANY : Env.DEFMEM);    // Memory into the callee
      default -> idx >= call.nargs()              // Check for args present
      ? new ConNode<>(Type.ALL) // Missing args, still wire (to keep FunNode neighbors) but will error out later.
      : new ProjNode(call, idx); // Normal args
      };
      actual._live = arg._live; // Set it before CSE during init1
      arg.add_def(actual.init1());
      work.add(actual);       // Also on the Combo worklist
      if( arg._val.is_con() ) // Added an edge, value may change or go in-error
        arg.add_work_defs(work); // So use-liveness changes
    }

    // Add matching control to function via a CallGraph edge.
    Node cep = new CEProjNode(call).init1();
    fun.add_def(work.add(cep));
    work.add(fun);
    for( Node use : fun._uses ) work.add(use);
    if( fun instanceof ThunkNode ) GVN.add_reduce_uses(fun);
    if( fun instanceof FunNode ) GVN.add_inline((FunNode)fun);
  }

  // Merge call-graph edges, but the call-graph is not discovered prior to GCP.
  // If the Call's type includes all-functions, then the CallEpi must assume
  // unwired Returns may yet appear, and be conservative.  Otherwise it can
  // just meet the set of known functions.
  @Override public Type value(GVNGCM.Mode opt_mode) {
    if( _is_copy ) return _val; // A copy
    Type tin0 = val(0);
    if( !(tin0 instanceof TypeTuple) )
      return tin0.oob();     // Weird stuff?
    TypeTuple tcall = (TypeTuple)tin0;
    if( tcall._ts.length < ARG_IDX ) return tcall.oob(); // Weird stuff

    // Get Call result.  If the Call args are in-error, then the Call is called
    // and we flow types to the post-call.... BUT the bad args are NOT passed
    // along to the function being called.
    // tcall[0] = Control
    // tcall[1] = Memory passed around the functions.
    // tcall[2] = Display
    // tcall[3+]= Arg types
    Type ctl = CallNode.tctl(tcall); // Call is reached or not?
    if( ctl != Type.CTRL && ctl != Type.ALL )
      return TypeTuple.CALLE.dual();
    TypeFunPtr tfptr= CallNode.ttfp(tcall);  // Peel apart Call tuple
    TypeMemPtr tescs= CallNode.tesc(tcall);  // Peel apart Call tuple

    // Fidxes; if still in the parser, assuming calling everything
    BitsFun fidxs = tfptr.fidxs();
    // NO fidxs, means we're not calling anything.
    if( fidxs==BitsFun.EMPTY ) return TypeTuple.make(Type.CTRL,TypeMem.ANYMEM,Type.ANY);
    if( fidxs.above_center() ) return TypeTuple.make(Type.CTRL,TypeMem.ANYMEM,Type.ANY); // Not resolved yet

    // Default memory: global worst-case scenario
    TypeMem defmem = Env.DEFMEM._val instanceof TypeMem
      ? (TypeMem)Env.DEFMEM._val
      : Env.DEFMEM._val.oob(TypeMem.ALLMEM);

    // Any not-wired unknown call targets?
    if( fidxs!=BitsFun.FULL ) {
      // If fidxs includes a parent fidx, then it was split - currently exactly
      // in two.  If both children are wired, then proceed to merge both
      // children as expected; same if the parent is still wired (perhaps with
      // some children).  If only 1 child is wired, then we have an extra fidx
      // for a not-wired child.  If this fidx is really some unknown caller we
      // would have to get very conservative; but it's actually just a recently
      // split child fidx.  If we get the RetNode via FunNode.find_fidx we suffer
      // the non-local progress curse.  If we get very conservative, we end up
      // rolling backwards (original fidx returned int; each split will only
      // ever return int-or-better).  So instead we "freeze in place".
      outerloop:
      for( int fidx : fidxs ) {
        int kids=0;
        for( int i=0; i<nwired(); i++ ) {
          int rfidx = wired(i)._fidx;
          if( fidx == rfidx ) continue outerloop; // Directly wired is always OK
          if( fidx == BitsFun.parent(rfidx) ) kids++; // Count wired kids of a parent fidx
        }
        if( BitsFun.is_parent(fidx) ) { // Child of a split parent, need both kids wired
          if( kids==2 ) continue;       // Both kids wired, this is ok
          return _val;                  // "Freeze in place"
        }
        if( !opt_mode._CG ) // Before GCP?  Fidx is an unwired unknown call target
          { fidxs = BitsFun.FULL; break; }
      }
    }

    // Compute call-return value from all callee returns
    Type trez = Type   .ANY;
    Type tmem = TypeMem.ANYMEM;
    CallNode call = call();
    ErrMsg err = call.err(true);
    if( fidxs == BitsFun.FULL ||  // Called something unknown
        err!=null  ) { // Call is in-error
      trez = Type.ALL;            // Unknown target does worst thing
      tmem = defmem;
    } else {                      // All targets are known & wired
      for( int i=0; i<nwired(); i++ ) {
        RetNode ret = wired(i);
        if( fidxs.test_recur(ret._fidx) ) { // Can be wired, but later fidx is removed
          Type tret = ret._val;
          if( !(tret instanceof TypeTuple) ) tret = tret.oob(TypeTuple.RET);
          tmem = tmem.meet(((TypeTuple)tret).at(MEM_IDX));
          trez = trez.meet(((TypeTuple)tret).at(REZ_IDX));
        }
      }
    }

    Type premem = call().mem()._val;
    TypeMem caller_mem = premem instanceof TypeMem ? (TypeMem)premem : premem.oob(TypeMem.ALLMEM);

    // If no memory projection, then do not compute memory
    TypeMem tmem3;
    if( (_keep==0 && ProjNode.proj(this,MEM_IDX)==null) || call().mem()==null ) {
      tmem3 = TypeMem.ANYMEM;
    } else {
      // Build epilog memory.

      // Approximate "live out of call", includes things that are alive before
      // the call but not flowing in.  Catches all the "new in call" returns.
      TypeMem post_call = (TypeMem)tmem;
      tmem3 = live_out(caller_mem,post_call,trez,tescs._aliases,opt_mode._CG ? null : defmem);
    }

    // Attempt to lift the result, based on HM types.  Walk the input HM type
    // and GCP flow type in parallel and create a mapping.  Then walk the
    // output HM type and CCP flow type in parallel, and join output CCP types
    // with the matching input CCP type.
    if( Combo.DO_HM && opt_mode._CG && err==null ) {
      // Walk the inputs, building a mapping
      TV2.T2MAP.clear();
      for( int i=DSP_IDX; i<call._defs._len-1; i++ )
        { TV2.WDUPS.clear(); call.tvar(i).walk_types_in(caller_mem,call.val(i)); }
      // Walk the outputs, building an improved result
      Type trez_sharp = tmem3.sharptr(trez);
      Type trez_lift = tvar().walk_types_out(trez_sharp,this);
      Type trez_lift_dull = trez_lift.simple_ptr();
      if( trez_lift instanceof TypeMemPtr )
        tmem3 = tmem3.lift_at((TypeMemPtr)trez_lift);  // Upgrade memory result
      trez = trez_lift_dull.join(trez);                // Upgrade scalar result
    }

    return TypeTuple.make(Type.CTRL,tmem3,trez);
  }


  static BitsAlias esc_out( TypeMem tmem, Type trez ) {
    if( trez == Type.XNIL || trez == Type.NIL ) return BitsAlias.EMPTY;
    if( trez instanceof TypeFunPtr ) trez = ((TypeFunPtr)trez)._dsp;
    if( trez instanceof TypeMemPtr )
      return tmem.all_reaching_aliases(((TypeMemPtr)trez)._aliases);
    return TypeMemPtr.OOP0.dual().isa(trez) ? BitsAlias.NZERO : BitsAlias.EMPTY;
  }

  // Approximate "live out of call", includes things that are alive before
  // the call but not flowing in.  Catches all the "new in call" returns.

  // A little profiling on some simple test cases (so the stats are suspect on
  // larger programs) shows:
  // - About 80% of the time, the esc_in and esc_out are full, and about 20%
  //   they are empty and about 1% they are sorta reasonable.
  // - About 50% of the time, the caller_mem and post_call mem are the same.
  //   The actual memory contents are all over the map.
  // - The length hits the largest alias pretty quick.
  static TypeMem live_out(TypeMem caller_mem, TypeMem post_call, Type trez, BitsAlias esc_in, TypeMem defmem) {
    // Fast cutout for same memory
    if( caller_mem == post_call )
      return caller_mem;        // Not joining with DEFMEM
    BitsAlias esc_out = esc_out(post_call,trez);
    int len = Math.max(Math.max(caller_mem.len(),post_call.len()),esc_out.max()+1);
    if( defmem!=null ) len = Math.max(len,defmem.len());

    // Fast cutout for full aliases
    if( esc_in ==BitsAlias.FULL || esc_in ==BitsAlias.NZERO ||
        esc_out==BitsAlias.FULL || esc_out==BitsAlias.NZERO ) {
      TypeMem mt = (TypeMem)caller_mem.meet(post_call);
      return defmem==null ? mt : (TypeMem)mt.join(defmem);
    }

    // Fast cutout for empty aliases
    if( esc_in ==BitsAlias.EMPTY && esc_out==BitsAlias.EMPTY )
      return defmem==null ? caller_mem : (TypeMem)caller_mem.join(defmem);

    // TODO: Wildly inefficient, but perhaps not all that common
    TypeObj[] pubs = new TypeObj[len];
    for( int i=1; i<pubs.length; i++ ) {
      boolean ein  = esc_in .test_recur(i);
      boolean eout = esc_out.test_recur(i);
      TypeObj pre = caller_mem.at(i);
      TypeObj obj = ein || eout ? (TypeObj)(pre.meet(post_call.at(i))) : pre;
      // Before GCP, must use DefMem to keeps types strong as the Parser
      // During GCP, can lift default actual memories.
      if( defmem!=null ) // Before GCP, must use DefMem to keeps types strong as the Parser
        obj = (TypeObj)obj.join(defmem.at(i));
      pubs[i] = obj;
    }
    TypeMem tmem3 = TypeMem.make0(pubs);
    return tmem3;
  }

  @Override public void add_work_use_extra(Work work, Node chg) {
    if( chg instanceof CallNode ) {    // If the Call changes value
      work.add(chg.in(MEM_IDX));       // The called memory   changes liveness
      work.add(((CallNode)chg).fdx()); // The called function changes liveness
      for( int i=0; i<nwired(); i++ )  // Called returns change liveness
        work.add(wired(i));
    }
  }

  // Sanity check
  boolean sane_wiring() {
    CallNode call = call();
    for( int i=0; i<nwired(); i++ ) {
      RetNode ret = wired(i);
      if( ret.is_copy() ) return true; // Abort check, will be misaligned until dead path removed
      FunNode fun = ret.fun();
      boolean found=false;
      for( Node def : fun._defs )
        if( def instanceof CEProjNode && def.in(0)==call )
          { found=true; break; }
      if( !found ) return false;
    }
    return true;
  }

  @Override public Node is_copy(int idx) { return _is_copy ? in(idx) : null; }

  private CallEpiNode set_is_copy( Node ctl, Node mem, Node rez ) {
    CallNode call = call();
    if( FunNode._must_inline == call._uid ) // Assert an expected inlining happens
      FunNode._must_inline = 0;
    if( mem instanceof IntrinsicNode ) // Better error message for Intrinsic if Call args are bad
      ((IntrinsicNode)mem)._badargs = call._badargs[1];
    call._is_copy=_is_copy=true;
    // Memory was split at the Call, according to the escapes aliases, and
    // rejoined at the CallEpi.  We need to make that explicit here.
    GVNGCM.retype_mem(null,call,this,false);

    Env.GVN.add_reduce_uses(call);
    Env.GVN.add_reduce_uses(this);
    while( _defs._len>0 ) pop();
    add_def(ctl);
    add_def(mem);
    add_def(rez);
    return this;
  }

  CallEpiNode unwire(CallNode call, RetNode ret) {
    assert sane_wiring();
    if( !ret.is_copy() ) {
      FunNode fun = ret.fun();
      for( int i = 1; i < fun._defs._len; i++ ) // Unwire
        if( fun.in(i).in(0) == call ) {
          fun.set_def(i, Env.XCTRL);
          Env.GVN.add_flow(fun);
          for( Node use : fun._uses ) {
            Env.GVN.add_flow(use); // Dead path, all Phis can lift
            Env.GVN.add_flow_defs(use); // All Phi uses lose a use
          }
          break;
        }
    }
    remove(_defs.find(ret));
    Env.GVN.add_reduce(ret);
    assert sane_wiring();
    return this;
  }

  @Override public TypeMem all_live() { return TypeMem.ALLMEM; }
  // Compute local contribution of use liveness to this def.  If the call is
  // Unresolved, then none of CallEpi targets are (yet) alive.
  @Override public TypeMem live_use(GVNGCM.Mode opt_mode, Node def ) {
    assert _keep==0 || _live==all_live();
    if( _is_copy ) return def._live; // A copy
    // Not a copy
    if( def==in(0) ) return _live; // The Call
    if( def==in(1) ) return opt_mode._CG ? TypeMem.DEAD : _live; // The DefMem
    // Wired return.
    // The given function is alive, only if the Call will Call it.
    Type tcall = call()._val;
    if( !(tcall instanceof TypeTuple) ) return tcall.above_center() ? TypeMem.DEAD : _live;
    BitsFun fidxs = CallNode.ttfp(tcall).fidxs();
    int fidx = ((RetNode)def).fidx();
    if( fidxs.above_center() || !fidxs.test_recur(fidx) )
      return TypeMem.DEAD;    // Call does not call this, so not alive.
    return _live;
  }

  @Override public boolean unify( Work work ) {
    assert !_is_copy;
    if( tvar().is_err() ) return false; // Already sick, nothing to do
    CallNode call = call();
    Node fdx = call.fdx();
    TV2 tfun = fdx.tvar();
    if( tfun.is_err() )         // Unify with function error
      return tvar().unify(tfun,work);

    // If the function is not a function, make it a function
    boolean progress = false;
    if( !tfun.is_fun() ) {
      if( work==null ) return true;
      NonBlockingHashMap<String,TV2> args = new NonBlockingHashMap<>();
      // The display is extracted from the FunPtr and is not the function itself
      //args.put("2",TV2.make_leaf(fdx,"CallEpi_unify"));
      for( int i=ARG_IDX; i<call._defs._len; i++ )
        args.put((""+i).intern(),call.tvar(i));
      args.put(" ret",tvar());
      TV2 nfun = TV2.make_fun(this, fdx._val, args, "CallEpi_unify");
      progress = tfun.unify(nfun, work);
      tfun = tfun.find();
    }
    // TODO: Handle Thunks

    if( tfun.nargs() != call.nargs()-ARG_IDX ) //
      return tvar().unify(TV2.make_err(this,"Mismatched argument lengths","CallEpi_unify"),work);

    // Check for progress amongst args
    for( int i=ARG_IDX; i<call._defs._len; i++ ) {
      TV2 actual = call.tvar(i);
      TV2 formal = tfun.get((""+i).intern());
      progress |= actual.unify(formal,work);
      if( progress && work==null ) return true; // Early exit
      if( (tfun=tfun.find()).is_err() ) throw unimpl();
    }
    // Check for progress on the return
    progress |= tvar().unify(tfun.get(" ret"),work);
    if( (tfun=tfun.find()).is_err() ) return tvar().unify(tfun,work);

    return progress;
  }
  
  @Override public void add_work_hm(Work work) {
    super.add_work_hm(work);
    // My tvar changed, so my value lift changes
    work.add(this);
  }

  @Override Node is_pure_call() { return in(0) instanceof CallNode ? call().is_pure_call() : null; }
  // Return the set of updatable memory - including everything reachable from
  // every call argument (including the display), and any calls reachable from
  // there, transitively through memory.
  //
  // In practice, just the no-escape aliases
  @Override BitsAlias escapees() { return BitsAlias.FULL; }
}
