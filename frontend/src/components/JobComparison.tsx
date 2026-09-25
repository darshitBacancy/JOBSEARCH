import type { Comparison } from '../types';
import { Markdown } from '../utils/markdown';
import { useJobActions } from './JobActionsContext';

export function JobComparison({ comparison }: { comparison: Comparison }) {
  const { openDetails } = useJobActions();
  const { jobs, rows, summary } = comparison;

  return (
    <section className="comparison" aria-label="Job comparison">
      <h3 className="comparison-title">Comparing {jobs.length} jobs</h3>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th scope="col" className="row-label">
                Field
              </th>
              {jobs.map((j, i) => (
                <th key={j.id} scope="col">
                  <button type="button" className="link-btn" onClick={() => openDetails(j.id)}>
                    {String.fromCharCode(65 + i)}. Job #{j.id}
                  </button>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.label}>
                <th scope="row" className="row-label">
                  {row.label}
                </th>
                {row.values.map((v, i) => (
                  <td key={i}>{v || '—'}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {summary && (
        <div className="comparison-summary">
          <p className="md-h">Summary</p>
          <Markdown text={summary} onJobRef={openDetails} />
        </div>
      )}
    </section>
  );
}
