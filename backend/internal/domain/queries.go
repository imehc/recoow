package domain

type RecordQuery struct {
	Table     string
	After     string
	Limit     int
	Search    string
	FromMs    int64
	ToMs      int64
	DateField string
}
type RecordPage struct {
	Rows       []Row  `json:"rows"`
	NextCursor string `json:"nextCursor"`
	More       bool   `json:"more"`
}
type Statistics struct {
	RecordCounts            map[string]int64 `json:"recordCounts"`
	BillExpenseCents        int64            `json:"billExpenseCents"`
	BillIncomeCents         int64            `json:"billIncomeCents"`
	BillStoredValueUseCents int64            `json:"billStoredValueUseCents"`
}
