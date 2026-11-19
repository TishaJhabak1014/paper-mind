import React from 'react';
import { Box, Button, CircularProgress, LinearProgress, Paper, Typography } from '@mui/material';

export default function GraphProgressNotice({ message, progress, error = false, warning = false, actionLabel, onAction }) {
  const determinate = Number.isFinite(progress?.total) && progress.total > 0;
  const value = determinate ? Math.min(100, (progress.completed / progress.total) * 100) : undefined;
  const color = error ? 'error' : warning ? 'warning' : 'text.secondary';

  return (
    <Paper
      role={error ? 'alert' : 'status'}
      aria-live="polite"
      variant="outlined"
      sx={{ p: 1.5, mb: 2, borderRadius: 2, bgcolor: warning ? '#fff8e1' : 'background.paper', borderColor: warning ? 'warning.light' : undefined }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}>
        {!error && !warning && <CircularProgress size={18} aria-hidden="true" />}
        <Typography variant="body2" color={color}>
          {message}
        </Typography>
      </Box>
      {(error || warning) && actionLabel && onAction && (
        <Button size="small" onClick={onAction} sx={{ mt: 0.5 }}>{actionLabel}</Button>
      )}
      {!error && !warning && (
        <LinearProgress
          variant={determinate ? 'determinate' : 'indeterminate'}
          value={value}
          sx={{ mt: 1.25, borderRadius: 1 }}
        />
      )}
    </Paper>
  );
}
