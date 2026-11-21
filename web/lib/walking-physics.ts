export type WalkingBody={x:number;z:number;vx:number;vz:number};
type Point={x:number;z:number};
type WalkingOptions={
  speed:number;
  segmentClear:(from:Point,to:Point)=>boolean;
  // Return the reachable point along this ray for the given effort budget.
  // A gentle descent may extend beyond `to`; less budget must never go farther.
  terrainStep?:(from:Point,to:Point,budget:number)=>Point;
};

const maxFrame=.1,maxStep=1/120,acceleration=20,braking=45,restSpeed=.01;
const collisionIterations=14,epsilon=1e-10;

function terrainPoint(from:Point,to:Point,budget:number,options:WalkingOptions):Point {
  if(budget<=epsilon)return {...from};
  if(options.terrainStep) {
    const point=options.terrainStep({...from},{...to},budget);
    return Number.isFinite(point.x)&&Number.isFinite(point.z)?{...point}:{...from};
  }
  const length=Math.hypot(to.x-from.x,to.z-from.z),fraction=length>0?Math.min(1,budget/length):0;
  return {x:from.x+(to.x-from.x)*fraction,z:from.z+(to.z-from.z)*fraction};
}

function sweep(from:Point,to:Point,budget:number,options:WalkingOptions) {
  const end=terrainPoint(from,to,budget,options);
  if(options.segmentClear(from,end))return {point:end,fraction:1,blocked:false};
  // Search effort, rather than geometric distance: the remainder still belongs
  // to this substep when a hillside meets a wall and the body starts to slide.
  let low=0,high=1,point={...from};
  for(let index=0;index<collisionIterations;index++) {
    const fraction=(low+high)/2,candidate=terrainPoint(from,to,budget*fraction,options);
    if(options.segmentClear(from,candidate)){low=fraction;point=candidate;}
    else high=fraction;
  }
  return {point,fraction:low,blocked:true};
}

function move(body:WalkingBody,dx:number,dz:number,options:WalkingOptions):WalkingBody {
  const from={x:body.x,z:body.z},budget=Math.hypot(dx,dz);
  if(budget<=epsilon)return body;
  const direct=sweep(from,{x:from.x+dx,z:from.z+dz},budget,options);
  if(!direct.blocked)return {...body,...direct.point};
  const remaining=1-direct.fraction,contact=direct.point;
  const x=Math.abs(dx)>epsilon?sweep(contact,{x:contact.x+dx*remaining,z:contact.z},Math.abs(dx)*remaining,options):undefined;
  const z=Math.abs(dz)>epsilon?sweep(contact,{x:contact.x,z:contact.z+dz*remaining},Math.abs(dz)*remaining,options):undefined;
  const progress=(candidate:typeof x)=>candidate?Math.hypot(candidate.point.x-contact.x,candidate.point.z-contact.z):0;
  // Both choices begin at contact. Choosing one continuous slide prevents an
  // artificial pair of axis moves from stepping around a blocked corner.
  const slideX=Boolean(x&&(!z||(!x.blocked&&z.blocked)||(x.blocked===z.blocked&&progress(x)>=progress(z))));
  const slide=slideX?x:z;
  return {...body,...(slide?.point??contact),vx:slideX&&x&&!x.blocked?body.vx:0,vz:!slideX&&z&&!z.blocked?body.vz:0};
}

/** Integrate walking velocity in seconds; collision callbacks check the whole segment. */
export function stepWalkingBody(body:WalkingBody,input:Point,dt:number,options:WalkingOptions):WalkingBody {
  let result={...body};
  if(!Number.isFinite(dt)||dt<=0)return result;
  const duration=Math.min(dt,maxFrame),steps=Math.ceil(duration/maxStep),h=duration/steps;
  const speed=Number.isFinite(options.speed)?Math.max(0,options.speed):0;
  const ix=Number.isFinite(input.x)?input.x:0,iz=Number.isFinite(input.z)?input.z:0;
  const scale=speed/Math.max(1,Math.hypot(ix,iz)),targetX=ix*scale,targetZ=iz*scale;
  const resting=targetX===0&&targetZ===0;
  for(let index=0;index<steps;index++) {
    const currentSpeed=Math.hypot(result.vx,result.vz),rate=resting?braking:acceleration;
    // Integrating the exponential exactly keeps displacement consistent across
    // frame rates, including the last fraction of a frame before coming to rest.
    const active=resting?Math.min(h,Math.max(0,Math.log(currentSpeed/restSpeed)/rate)):h;
    const decay=Math.exp(-rate*active),integral=(1-decay)/rate;
    const dx=targetX*active+(result.vx-targetX)*integral,dz=targetZ*active+(result.vz-targetZ)*integral;
    const vx=resting&&active<h?0:targetX+(result.vx-targetX)*decay;
    const vz=resting&&active<h?0:targetZ+(result.vz-targetZ)*decay;
    result=move({...result,vx,vz},dx,dz,options);
  }
  return result;
}
