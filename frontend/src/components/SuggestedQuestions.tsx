export const DEFAULT_SUGGESTIONS = [
  'Find remote Java Spring Boot jobs',
  'Find Spring Boot jobs in India',
  'Show jobs above ₹12 LPA',
  'Find React jobs for 3-5 years experience',
  'Show me remote jobs requiring AWS',
  'Compare the best matching jobs',
];

interface Props {
  questions?: string[];
  onPick: (q: string) => void;
  disabled?: boolean;
  compact?: boolean;
}

export function SuggestedQuestions({ questions = DEFAULT_SUGGESTIONS, onPick, disabled, compact }: Props) {
  if (!questions.length) return null;
  return (
    <div className={`suggestions${compact ? ' compact' : ''}`} role="group" aria-label="Suggested questions">
      {questions.map((q) => (
        <button key={q} type="button" className="suggestion" onClick={() => onPick(q)} disabled={disabled}>
          {q}
        </button>
      ))}
    </div>
  );
}
