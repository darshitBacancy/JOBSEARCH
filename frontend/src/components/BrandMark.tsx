/** The app's logo: a briefcase on an indigo→violet tile. */
export function BrandMark({ size = 32, className = '' }: { size?: number; className?: string }) {
  return (
    <span className={`brand-mark ${className}`} style={{ width: size, height: size }} aria-hidden="true">
      <svg viewBox="0 0 32 32" width={Math.round(size * 0.62)} height={Math.round(size * 0.62)} fill="none">
        <path d="M11 9.5V8a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v1.5" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
        <rect x="4" y="9.5" width="24" height="17" rx="4" stroke="currentColor" strokeWidth="2.2" />
        <path d="M4.5 16.5c7 3 16 3 23 0" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
        <path d="M16 19.2v2.4" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" />
      </svg>
    </span>
  );
}
