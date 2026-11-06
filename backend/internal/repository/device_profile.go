package repository

import (
	"encoding/json"
	"github.com/imehc/recoow/backend/internal/domain"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
	"github.com/imehc/recoow/backend/internal/service"
)

func deviceProfile(profiles []domain.DeviceProfile) (*domain.DeviceProfile, json.RawMessage, error) {
	if len(profiles) == 0 {
		return nil, nil, nil
	}
	profile := profiles[0]
	if err := service.ValidateDeviceProfile(profile); err != nil {
		return nil, nil, err
	}
	raw, err := json.Marshal(profile)
	return &profile, raw, err
}

func cloneDevice(device domain.Device) domain.Device {
	if device.Profile != nil {
		copy := *device.Profile
		device.Profile = &copy
	}
	return device
}

func matchingOrigin(device domain.Device, origin *domain.ChangeOrigin) bool {
	return origin != nil && origin.Client != "unknown" && device.Profile != nil &&
		device.Profile.LocalDeviceID == origin.DeviceID && device.Profile.Client == origin.Client
}

func (s *memoryStore) UpdateDeviceProfile(accountID, deviceID, name string, profile domain.DeviceProfile) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := service.ValidateDeviceName(name); err != nil {
		return err
	}
	if err := service.ValidateDeviceProfile(profile); err != nil {
		return err
	}
	d, ok := s.devices[deviceID]
	if !ok || d.AccountID != accountID {
		return domain.ErrNotFound
	}
	d.Profile = &profile
	if name != "" {
		d.Name = name
	}
	s.devices[deviceID] = d
	return nil
}

func (s *postgresStore) UpdateDeviceProfile(accountID, deviceID, name string, profile domain.DeviceProfile) error {
	if err := service.ValidateDeviceName(name); err != nil {
		return err
	}
	_, raw, err := deviceProfile([]domain.DeviceProfile{profile})
	if err != nil {
		return err
	}
	count, err := sqlgen.New(s.db.DB).UpdateDeviceProfile(s.ctx, sqlgen.UpdateDeviceProfileParams{AccountID: accountID, ID: deviceID, Name: name, Profile: raw})
	if err == nil && count == 0 {
		return domain.ErrNotFound
	}
	return err
}
