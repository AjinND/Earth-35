type Point={x:number;z:number};
type Segment={x:number;z:number;dx:number;dz:number;lengthSquared:number;index:number};
type Bounds={minX:number;maxX:number;minZ:number;maxZ:number};
type Node=Bounds&{segments?:Segment[];left?:Node;right?:Node};

/** Exact nearest-segment queries over a static survey, without scanning it all. */
export function segmentIndex(paths:readonly (readonly Point[])[]) {
  const segments:Segment[]=[];
  for(const points of paths)for(let i=1;i<points.length;i++) {
    const a=points[i-1],b=points[i],dx=b.x-a.x,dz=b.z-a.z;
    segments.push({x:a.x,z:a.z,dx,dz,lengthSquared:dx*dx+dz*dz,index:segments.length});
  }
  const build=(items:Segment[]):Node=>{
    const bounds:Bounds={minX:Infinity,maxX:-Infinity,minZ:Infinity,maxZ:-Infinity};
    for(const s of items) {
      bounds.minX=Math.min(bounds.minX,s.x,s.x+s.dx);bounds.maxX=Math.max(bounds.maxX,s.x,s.x+s.dx);
      bounds.minZ=Math.min(bounds.minZ,s.z,s.z+s.dz);bounds.maxZ=Math.max(bounds.maxZ,s.z,s.z+s.dz);
    }
    if(items.length<=8)return {...bounds,segments:items};
    const axis=bounds.maxX-bounds.minX>=bounds.maxZ-bounds.minZ?"x":"z";
    items.sort((a,b)=>axis==="x"?a.x+a.dx/2-b.x-b.dx/2:a.z+a.dz/2-b.z-b.dz/2);
    const mid=Math.floor(items.length/2);
    return {...bounds,left:build(items.slice(0,mid)),right:build(items.slice(mid))};
  };
  const root=build(segments);
  return (x:number,z:number)=>{
    let squaredDistance=Infinity,index=-1,fraction=0;
    const distance=(node:Bounds)=>{
      const dx=Math.max(node.minX-x,0,x-node.maxX),dz=Math.max(node.minZ-z,0,z-node.maxZ);
      return dx*dx+dz*dz;
    };
    const visit=(node:Node)=>{
      if(distance(node)>squaredDistance)return;
      if(node.segments) {
        for(const s of node.segments) {
          const t=s.lengthSquared===0?0:Math.max(0,Math.min(1,((x-s.x)*s.dx+(z-s.z)*s.dz)/s.lengthSquared));
          const px=x-s.x-t*s.dx,pz=z-s.z-t*s.dz,d=px*px+pz*pz;
          // Preserve survey order at shared vertices and equidistant segments.
          if(d<squaredDistance||(d===squaredDistance&&(index<0||s.index<index))){squaredDistance=d;index=s.index;fraction=t;}
        }
      }else {
        const left=node.left!,right=node.right!;
        if(distance(left)<=distance(right)){visit(left);visit(right);}else{visit(right);visit(left);}
      }
    };
    visit(root);return {distance:Math.sqrt(squaredDistance),index,fraction};
  };
}
