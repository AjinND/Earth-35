import {avatarStyle} from "./avatar";
import {footprintContains,outsideFootprint,type ObjectFootprint} from "./object-footprint";

export type ResidentPoint={id:string;x:number;z:number;y?:number;anchored?:boolean;obstacleAnchored?:boolean};
export type ResidentObstacle=ObjectFootprint;
export type ResidentProjection={right:{x:number;y:number;z:number};up:{x:number;y:number;z:number};elevation?:(x:number,z:number)=>number};
// Includes the plush body, swinging arms and selection ring.
export const residentSpacing=1.35;
const obstacleCellSize=8;
const obstacleIndexes=new WeakMap<readonly ResidentObstacle[],Map<string,ResidentObstacle[]>>();
function nearbyObstacles(obstacles:readonly ResidentObstacle[]) {
  let cells=obstacleIndexes.get(obstacles);
  if(!cells) {
    cells=new Map();
    for(const obstacle of obstacles) {
      const rx="radius" in obstacle?obstacle.radius:Math.abs(obstacle.cos)*obstacle.halfX+Math.abs(obstacle.sin)*obstacle.halfZ;
      const rz="radius" in obstacle?obstacle.radius:Math.abs(obstacle.sin)*obstacle.halfX+Math.abs(obstacle.cos)*obstacle.halfZ;
      for(let x=Math.floor((obstacle.x-rx)/obstacleCellSize);x<=Math.floor((obstacle.x+rx)/obstacleCellSize);x++)for(let z=Math.floor((obstacle.z-rz)/obstacleCellSize);z<=Math.floor((obstacle.z+rz)/obstacleCellSize);z++) {
        const key=`${x}:${z}`,bucket=cells.get(key)??[];bucket.push(obstacle);cells.set(key,bucket);
      }
    }
    obstacleIndexes.set(obstacles,cells);
  }
  return (p:{x:number;z:number})=>cells.get(`${Math.floor(p.x/obstacleCellSize)}:${Math.floor(p.z/obstacleCellSize)}`)??[];
}

/** Visual offsets only: world coordinates, actions and proximity stay authoritative. */
export function separateResidents(points:readonly ResidentPoint[],obstacles:readonly ResidentObstacle[]=[],projection?:ResidentProjection):ResidentPoint[] {
  const positions=points.map(p=>({...p})).sort((a,b)=>a.id<b.id?-1:a.id>b.id?1:0);
  const nearby=nearbyObstacles(obstacles);
  const minimumSquared=residentSpacing*residentSpacing;
  const elevation=projection?.elevation;
  const heightOffsets=new Map(elevation?positions.map(p=>{
    const ground=elevation(p.x,p.z);
    return [p.id,(p.y??ground)-ground] as const;
  }):[]);
  const groundHeight=(p:ResidentPoint,x=p.x,z=p.z)=>elevation?elevation(x,z)+heightOffsets.get(p.id)!:p.y;
  const updateHeight=(p:ResidentPoint)=>{if(elevation&&(!p.anchored||p.obstacleAnchored===false))p.y=groundHeight(p);};
  const obstacleFree=(p:ResidentPoint)=>nearby(p).every(o=>!footprintContains(p,o));
  const fixedToObstacle=(p:ResidentPoint)=>p.obstacleAnchored??p.anchored;
  const determinant=projection?projection.right.x*projection.up.z-projection.right.z*projection.up.x:0;
  // A perspective view at ground level has no invertible ground projection.
  const view=projection&&Math.abs(determinant)>.1?projection:undefined;
  const separation=(a:ResidentPoint,b:ResidentPoint)=>{
    const dx=b.x-a.x,dz=b.z-a.z,squared=dx*dx+dz*dz;
    if(view) {
      const dy=(b.y??0)-(a.y??0);
      const x=dx*view.right.x+dy*view.right.y+dz*view.right.z;
      const z=dx*view.up.x+dy*view.up.y+dz*view.up.z;
      const projectedSquared=x*x+z*z;
      if(projectedSquared<squared)return {dx:x,dz:z,squared:projectedSquared,projected:true};
    }
    return {dx,dz,squared,projected:false};
  };
  // Shared arrival coordinates need a direction per resident. Resolving the
  // first coincident pair alone puts the entire gathering on that pair's axis.
  const arrivals=positions.map(p=>p.anchored?.05:positions.reduce((distance,other)=>other===p?distance:Math.min(distance,Math.hypot(p.x-other.x,p.z-other.z)),.05));
  positions.forEach((p,i)=>{
    if(arrivals[i]>=.05)return;
    const seed=avatarStyle(p.id).seed,angle=(seed%360)*Math.PI/180;
    const radius=residentSpacing*(.22+(seed>>>8)%100*.001)*(1-arrivals[i]/.05);
    p.x+=Math.cos(angle)*radius;p.z+=Math.sin(angle)*radius;
    updateHeight(p);
  });
  const clearObstacles=(p:ResidentPoint)=>{
    if(fixedToObstacle(p))return;
    const angle=(avatarStyle(p.id).seed%360)*Math.PI/180;
    // Adjacent furniture can overlap after body clearance is added. Recheck
    // after each displacement; the free-ring fallback handles tight corners.
    for(let pass=0;pass<8;pass++) {
      let moved=false;
      for(const obstacle of nearby(p)) {
        const outside=outsideFootprint(p,obstacle,angle);
        if(outside===p)continue;
        p.x=outside.x;p.z=outside.z;moved=true;
      }
      if(!moved)break;
    }
    updateHeight(p);
  };
  positions.forEach(clearObstacles);
  for(let pass=0;pass<64;pass++) {
    let overlapping=false;
    for(let i=0;i<positions.length;i++)for(let j=i+1;j<positions.length;j++) {
      const a=positions[i],b=positions[j],{dx,dz,squared,projected}=separation(a,b);
      if(a.anchored&&b.anchored)continue;
      if(squared>=minimumSquared-1e-8)continue;
      overlapping=true;
      const distance=Math.sqrt(squared);
      const angle=distance<1e-6?(avatarStyle(`${a.id}:${b.id}`).seed%360)*Math.PI/180:0;
      let nx=distance<1e-6?Math.cos(angle):dx/distance,nz=distance<1e-6?Math.sin(angle):dz/distance;
      if(projected&&view) {
        // Resolve on the screen, then convert the offset back onto the ground.
        const x=(view.up.z*nx-view.right.z*nz)/determinant;
        nz=(view.right.x*nz-view.up.x*nx)/determinant;nx=x;
      }
      const offset=(residentSpacing-distance)+2e-6;
      const share=a.anchored||b.anchored?1:.5;
      if(!a.anchored){a.x-=nx*offset*share;a.z-=nz*offset*share;clearObstacles(a);}
      if(!b.anchored){b.x+=nx*offset*share;b.z+=nz*offset*share;clearObstacles(b);}
    }
    if(!overlapping) {
      if(positions.every(p=>fixedToObstacle(p)||obstacleFree(p)))return positions;
      break;
    }
  }
  // A dense crowd can exhaust relaxation. Place any remaining overlaps in the
  // nearest free ring so even a full venue never shares an avatar position.
  const fixed=(p:ResidentPoint)=>p.anchored&&(fixedToObstacle(p)||obstacleFree(p));
  const placed:ResidentPoint[]=positions.filter(fixed);
  for(const point of positions.filter(p=>!fixed(p))) {
    const free=(x:number,z:number)=>placed.every(p=>separation(p,{...point,x,z,...(elevation?{y:groundHeight(point,x,z)}:{})}).squared>=minimumSquared-1e-8)
      &&nearby({x,z}).every(o=>!footprintContains({x,z},o));
    if(!free(point.x,point.z)) {
      const x=point.x,z=point.z;
      search:for(let ring=1;ring<=Math.max(32,positions.length);ring++)for(let step=0;step<ring*6;step++) {
        const angle=step*Math.PI*2/(ring*6),nx=x+Math.cos(angle)*residentSpacing*ring,nz=z+Math.sin(angle)*residentSpacing*ring;
        if(free(nx,nz)){point.x=nx;point.z=nz;updateHeight(point);break search;}
      }
    }
    placed.push(point);
  }
  return positions;
}
