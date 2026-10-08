import {avatarStyle} from "./avatar";

export type ResidentPoint={id:string;x:number;z:number;anchored?:boolean};
export type ResidentObstacle={x:number;z:number;radius:number};
// Includes the plush body, swinging arms and selection ring.
export const residentSpacing=1.35;

/** Visual offsets only: world coordinates, actions and proximity stay authoritative. */
export function separateResidents(points:readonly ResidentPoint[],obstacles:readonly ResidentObstacle[]=[]):ResidentPoint[] {
  const positions=points.map(p=>({...p})).sort((a,b)=>a.id<b.id?-1:a.id>b.id?1:0);
  const minimumSquared=residentSpacing*residentSpacing;
  const clearObstacles=(p:ResidentPoint)=>{
    if(p.anchored)return;
    for(const obstacle of obstacles) {
      const dx=p.x-obstacle.x,dz=p.z-obstacle.z,distance=Math.hypot(dx,dz);
      if(distance>=obstacle.radius)continue;
      const angle=distance<1e-6?(avatarStyle(p.id).seed%360)*Math.PI/180:Math.atan2(dz,dx);
      p.x=obstacle.x+Math.cos(angle)*(obstacle.radius+1e-6);p.z=obstacle.z+Math.sin(angle)*(obstacle.radius+1e-6);
    }
  };
  positions.forEach(clearObstacles);
  for(let pass=0;pass<64;pass++) {
    let overlapping=false;
    for(let i=0;i<positions.length;i++)for(let j=i+1;j<positions.length;j++) {
      const a=positions[i],b=positions[j],dx=b.x-a.x,dz=b.z-a.z,squared=dx*dx+dz*dz;
      if(a.anchored&&b.anchored)continue;
      if(squared>=minimumSquared-1e-8)continue;
      overlapping=true;
      const distance=Math.sqrt(squared);
      const angle=distance<1e-6?(avatarStyle(`${a.id}:${b.id}`).seed%360)*Math.PI/180:0;
      const nx=distance<1e-6?Math.cos(angle):dx/distance,nz=distance<1e-6?Math.sin(angle):dz/distance;
      const offset=(residentSpacing-distance)+2e-6;
      const share=a.anchored||b.anchored?1:.5;
      if(!a.anchored){a.x-=nx*offset*share;a.z-=nz*offset*share;clearObstacles(a);}
      if(!b.anchored){b.x+=nx*offset*share;b.z+=nz*offset*share;clearObstacles(b);}
    }
    if(!overlapping)return positions;
  }
  // A dense crowd can exhaust relaxation. Place any remaining overlaps in the
  // nearest free ring so even a full venue never shares an avatar position.
  const placed:ResidentPoint[]=positions.filter(p=>p.anchored);
  for(const point of positions.filter(p=>!p.anchored)) {
    const free=(x:number,z:number)=>placed.every(p=>(p.x-x)**2+(p.z-z)**2>=minimumSquared-1e-8)
      &&obstacles.every(o=>(o.x-x)**2+(o.z-z)**2>=o.radius**2);
    if(!free(point.x,point.z)) {
      const x=point.x,z=point.z;
      search:for(let ring=1;ring<=positions.length;ring++)for(let step=0;step<ring*6;step++) {
        const angle=step*Math.PI*2/(ring*6),nx=x+Math.cos(angle)*residentSpacing*ring,nz=z+Math.sin(angle)*residentSpacing*ring;
        if(free(nx,nz)){point.x=nx;point.z=nz;break search;}
      }
    }
    placed.push(point);
  }
  return positions;
}
