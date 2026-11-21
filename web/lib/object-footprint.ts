export type Point={x:number;z:number};
export type CircleFootprint=Point&{kind?:"circle";radius:number};
export type BoxFootprint=Point&{kind:"box";halfX:number;halfZ:number;cos:number;sin:number};
export type ObjectFootprint=CircleFootprint|BoxFootprint;

export function boxFootprint(x:number,z:number,width:number,depth:number,rotation=0,padding=0):BoxFootprint {
  return {kind:"box",x,z,halfX:width/2+padding,halfZ:depth/2+padding,cos:Math.cos(rotation),sin:Math.sin(rotation)};
}
export function localPoint(point:Point,box:BoxFootprint):Point {
  const x=point.x-box.x,z=point.z-box.z;
  return {x:x*box.cos-z*box.sin,z:x*box.sin+z*box.cos};
}
export function footprintContains(point:Point,shape:ObjectFootprint):boolean {
  if("radius" in shape)return (point.x-shape.x)**2+(point.z-shape.z)**2<shape.radius**2;
  const p=localPoint(point,shape);return Math.abs(p.x)<shape.halfX&&Math.abs(p.z)<shape.halfZ;
}
export function paddedFootprint(shape:ObjectFootprint,padding:number):ObjectFootprint {
  return "radius" in shape?{...shape,radius:shape.radius+padding}:{...shape,halfX:shape.halfX+padding,halfZ:shape.halfZ+padding};
}
/** Full footprints, including rotated edges, rather than only object centres. */
export function footprintsOverlap(a:ObjectFootprint,b:ObjectFootprint,gap=0):boolean {
  if("radius" in a) {
    if("radius" in b)return Math.hypot(a.x-b.x,a.z-b.z)<a.radius+b.radius+gap-1e-8;
    const p=localPoint(a,b);
    return Math.hypot(Math.max(0,Math.abs(p.x)-b.halfX),Math.max(0,Math.abs(p.z)-b.halfZ))<a.radius+gap-1e-8;
  }
  if("radius" in b)return footprintsOverlap(b,a,gap);
  for(const axis of [{x:a.cos,z:-a.sin},{x:a.sin,z:a.cos},{x:b.cos,z:-b.sin},{x:b.sin,z:b.cos}]) {
    const extent=(box:BoxFootprint)=>Math.abs(axis.x*box.cos-axis.z*box.sin)*box.halfX+Math.abs(axis.x*box.sin+axis.z*box.cos)*box.halfZ;
    if(Math.abs((b.x-a.x)*axis.x+(b.z-a.z)*axis.z)>=extent(a)+extent(b)+gap-1e-8)return false;
  }
  return true;
}
export function footprintCorners(box:BoxFootprint):Point[] {
  return [-1,1].flatMap(x=>[-1,1].map(z=>({x:box.x+x*box.halfX*box.cos+z*box.halfZ*box.sin,z:box.z-x*box.halfX*box.sin+z*box.halfZ*box.cos})));
}
export function outsideFootprint(point:Point,shape:ObjectFootprint,angle=0):Point {
  if(!footprintContains(point,shape))return point;
  if("radius" in shape) {
    const dx=point.x-shape.x,dz=point.z-shape.z;
    const direction=Math.hypot(dx,dz)<1e-6?angle:Math.atan2(dz,dx);
    return {x:shape.x+Math.cos(direction)*(shape.radius+1e-6),z:shape.z+Math.sin(direction)*(shape.radius+1e-6)};
  }
  const p=localPoint(point,shape);
  if(shape.halfX-Math.abs(p.x)<shape.halfZ-Math.abs(p.z))p.x=(p.x<0?-1:1)*(shape.halfX+1e-6);
  else p.z=(p.z<0?-1:1)*(shape.halfZ+1e-6);
  return {x:shape.x+p.x*shape.cos+p.z*shape.sin,z:shape.z-p.x*shape.sin+p.z*shape.cos};
}
