export interface SubmitButtonProps {
  label: string;
  pendingLabel: string;
  pending: boolean;
  /** While above zero the button is disabled and shows the wait instead. */
  waitSeconds?: number;
}

export function SubmitButton({ label, pendingLabel, pending, waitSeconds = 0 }: SubmitButtonProps) {
  const waiting = waitSeconds > 0;
  let text = label;
  if (pending) {
    text = pendingLabel;
  } else if (waiting) {
    text = `Try again in ${String(waitSeconds)}s`;
  }

  return (
    <button
      type="submit"
      disabled={pending || waiting}
      aria-busy={pending}
      className="w-full rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800 focus:outline-none focus:ring-2 focus:ring-teal-600/40 disabled:cursor-not-allowed disabled:bg-slate-400"
    >
      {text}
    </button>
  );
}
