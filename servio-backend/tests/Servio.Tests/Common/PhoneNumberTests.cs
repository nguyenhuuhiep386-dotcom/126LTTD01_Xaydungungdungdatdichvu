using Servio.Api.Common;

namespace Servio.Tests.Common;

public sealed class PhoneNumberTests
{
    [Theory]
    [InlineData("0901234567", "+84901234567")]
    [InlineData("84901234567", "+84901234567")]
    [InlineData("+84 901 234 567", "+84901234567")]
    [InlineData("0381234567", "+84381234567")]
    public void Normalize_ReturnsE164_ForValidVietnameseMobile(string input, string expected)
    {
        Assert.Equal(expected, PhoneNumber.Normalize(input));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("123")]
    [InlineData("0201234567")]   // landline prefix
    [InlineData("09012345678")]  // too long
    public void Normalize_ReturnsNull_ForInvalidInput(string? input)
    {
        Assert.Null(PhoneNumber.Normalize(input));
    }

    [Fact]
    public void Mask_KeepsOnlyLastFourDigits()
    {
        Assert.Equal("********4567", PhoneNumber.Mask("+84901234567"));
    }
}
