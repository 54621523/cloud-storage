import { marked } from 'marked';
import hljs from 'highlight.js';


const renderer = new marked.Renderer();


renderer.code = ({ text, lang }: { text: string; lang?: string; escaped?: boolean }) => {
  const validLanguage = lang && hljs.getLanguage(lang) ? lang : 'plaintext';
  const highlighted = hljs.highlight(text, { language: validLanguage }).value;
  return `<pre><code class="hljs language-${validLanguage}">${highlighted}</code></pre>`;
};

marked.use({
  renderer,
  breaks: true,
  gfm: true
});

export function parseMarkdown(text: string, msgIndex?: number | null): string {
  const html = marked.parse(text || '', { async: false }) as string;

  if (msgIndex === undefined || msgIndex === null) {
    return html;
  }

  let inCode = false;
  return html.split(/(<[^>]*>)/).map(part => {
    if (part.startsWith('<')) {
      if (part.startsWith('<code') || part.startsWith('<pre')) inCode = true;
      if (part.startsWith('</code') || part.startsWith('</pre')) inCode = false;
      return part;
    }
    if (!inCode) {
      return part.replace(/\[([\d\s,]+)\]/g, (match: string, p1: string) => {
        const numbers = p1.split(',').map((n: string) => n.trim()).filter((n: string) => /^\d+$/.test(n));
        if (numbers.length === 0) return match;
        return numbers.map(
          (n: string) => `<sup class="cite-ref" data-msg-index="${msgIndex}" data-chunk-index="${n}">[${n}]</sup>`
        ).join('');
      });
    }
    return part;
  }).join('');
}

export function escapeHtml(text: string): string {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}
