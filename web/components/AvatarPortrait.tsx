import {avatarStyle} from "@/lib/avatar";
export default function AvatarPortrait({id,name}:{id:string;name:string}) {
  const s=avatarStyle(id);
  return <div className="avatar fluffy-portrait" role="img" aria-label={`${name}'s fluffy avatar`}>
    <svg viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="49" fill="#f2efe4"/>
      <g fill={s.coat} stroke="#fff9" strokeWidth="1.5"><ellipse cx="28" cy={s.longEars?22:31} rx="12" ry={s.longEars?20:12}/><ellipse cx="72" cy={s.longEars?22:31} rx="12" ry={s.longEars?20:12}/>
        <path d="M23 40 Q15 39 17 49 Q10 55 18 62 Q13 71 23 75 Q27 87 39 83 Q49 92 59 84 Q75 88 79 76 Q90 72 84 61 Q93 52 84 46 Q83 34 73 36 Q62 27 52 34 Q36 28 29 38Z"/>
      </g><ellipse cx="50" cy="67" rx="19" ry="14" fill="#fff5df"/>
      <g fill="#343b36"><ellipse cx="35" cy="53" rx="3.5" ry="4.5"/><ellipse cx="65" cy="53" rx="3.5" ry="4.5"/><path d="M45 62 Q50 58 55 62 Q50 70 45 62"/></g>
      <g fill="white"><circle cx="34" cy="51" r="1.3"/><circle cx="64" cy="51" r="1.3"/></g><path d="M42 70 Q50 76 58 70" fill="none" stroke="#75624f" strokeWidth="2" strokeLinecap="round"/>
      <path d="M28 82 Q50 90 72 82 L70 90 Q50 98 30 90Z" fill={s.accent}/>
    </svg>
  </div>;
}
