import * as THREE from "three";
import type {IslandPoint} from "./island";

/** A continuous street surface with joined bends and rounded terminal corners. */
export function roadSurfaceGeometry(points:readonly IslandPoint[],width=3.2,y=.04) {
  const centerline=points.filter((p,i)=>i===0||Math.hypot(p.x-points[i-1].x,p.z-points[i-1].z)>.000001);
  const vertices:number[]=[],indices:number[]=[],radius=width/2;
  if(centerline.length<2)return new THREE.BufferGeometry();
  const normal=(a:IslandPoint,b:IslandPoint)=>{
    const length=Math.hypot(b.x-a.x,b.z-a.z);
    return {x:(b.z-a.z)/length,z:-(b.x-a.x)/length};
  };
  centerline.forEach((p,i)=>{
    const incoming=normal(centerline[Math.max(0,i-1)],centerline[i===0?1:i]);
    const outgoing=normal(centerline[i===centerline.length-1?i-1:i],centerline[Math.min(centerline.length-1,i+1)]);
    const denominator=1+incoming.x*outgoing.x+incoming.z*outgoing.z;
    // Intersect adjacent edges so the asphalt cannot narrow or leave a seam
    // at a bend. Surveyed streets curve gently; cap very sharp synthetic bends.
    const scale=radius/Math.max(.5,denominator);
    const offset={x:(incoming.x+outgoing.x)*scale,z:(incoming.z+outgoing.z)*scale};
    for(const side of [-1,1])vertices.push(p.x+offset.x*side,y,p.z+offset.z*side);
    if(i<centerline.length-1){const n=i*2;indices.push(n,n+2,n+1,n+1,n+2,n+3);}
  });
  // Streets meeting at a right-angle endpoint otherwise leave the outer
  // quarter of the junction uncovered. Caps also close short terminal streets.
  for(const p of [centerline[0],centerline.at(-1)!]) {
    const center=vertices.length/3,segments=32;
    vertices.push(p.x,y,p.z);
    for(let i=0;i<segments;i++) {
      const angle=2*Math.PI*i/segments;
      vertices.push(p.x+Math.cos(angle)*radius,y,p.z+Math.sin(angle)*radius);
      indices.push(center,center+1+(i+1)%segments,center+1+i);
    }
  }
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute("position",new THREE.Float32BufferAttribute(vertices,3));
  geometry.setIndex(indices);geometry.computeVertexNormals();return geometry;
}
