export interface PagerProps {
  label: string;
  /** Zero-based, as the API counts. */
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}

/** Previous / next for one PageResponse list; hidden when everything fits on one page. */
export function Pager({ label, page, totalPages, onChange }: PagerProps) {
  if (totalPages <= 1 && page === 0) {
    return null;
  }
  return (
    <nav aria-label={label} className="mt-6 flex items-center gap-4 text-sm">
      <button
        type="button"
        disabled={page === 0}
        onClick={() => {
          onChange(page - 1);
        }}
        className="font-medium text-teal-700 underline disabled:text-slate-400 disabled:no-underline"
      >
        Previous
      </button>
      <span>
        Page {page + 1} of {Math.max(totalPages, 1)}
      </span>
      <button
        type="button"
        disabled={page + 1 >= totalPages}
        onClick={() => {
          onChange(page + 1);
        }}
        className="font-medium text-teal-700 underline disabled:text-slate-400 disabled:no-underline"
      >
        Next
      </button>
    </nav>
  );
}
